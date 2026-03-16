package com.apachecamel.mini_integration_platform.service.ai;

import com.apachecamel.mini_integration_platform.model.document.AiAnalysisDocument;
import com.apachecamel.mini_integration_platform.model.document.ExceptionDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ExceptionAnalysisAgent
 *
 * A genuine AI Agent that analyses exception events using an agentic loop.
 *
 * Unlike the previous ExceptionAnalysisService (which made a single AI call),
 * this agent:
 *   1. Reasons about what information it needs
 *   2. Calls tools to gather that information from MongoDB
 *   3. Receives tool results and reasons again
 *   4. Loops until it has enough context to produce its final analysis
 *   5. Always sends an email with the complete message journey + analysis
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  AGENTIC LOOP                                                           │
 * │                                                                         │
 * │  Turn 1: Agent receives exception details                               │
 * │          → decides to call checkAuditHistory(originalMessageId)        │
 * │                                                                         │
 * │  Turn 2: Agent receives audit history (all successful route legs)       │
 * │          → decides to call checkExceptionDetail(originalMessageId)     │
 * │                                                                         │
 * │  Turn 3: Agent receives exception detail (failed leg + stacktrace)     │
 * │          → has full picture — produces final analysis JSON             │
 * │          → loop ends                                                    │
 * │                                                                         │
 * │  NotificationService builds journey email and sends via SMTP           │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Tools available to the agent:
 *   - checkAuditHistory(originalMessageId)    → List of successful route legs
 *   - checkExceptionDetail(originalMessageId) → Failed leg + exception info
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionAnalysisAgent {

  private final AgentToolService    agentToolService;
  private final NotificationService notificationService;
  private final ObjectMapper        objectMapper;

  @Value("${groq.api.key}")
  private String apiKey;

  @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
  private String apiUrl;

  @Value("${groq.api.model:llama-3.3-70b-versatile}")
  private String model;

  @Value("${groq.api.max-tokens:2048}")
  private int maxTokens;

  private static final Duration TIMEOUT     = Duration.ofSeconds(30);
  private static final int      MAX_TURNS   = 6;   // safety cap on agent loop

  // ── Tool name constants ────────────────────────────────────────────────────
  private static final String TOOL_AUDIT_HISTORY     = "checkAuditHistory";
  private static final String TOOL_EXCEPTION_DETAIL  = "checkExceptionDetail";

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Entry point called by ExceptionRoute wireTap.
   * Runs the full agentic loop and sends the result as an email.
   *
   * @param document  the saved ExceptionDocument from MongoDB
   */
  public void analyse(ExceptionDocument document) {
    log.info("[ExceptionAnalysisAgent] Starting agentic analysis — id='{}' msgId='{}' route='{}'",
            document.getId(),
            document.getOriginalMessageId(),
            document.getRouteInfo() != null ? document.getRouteInfo().getRouteName() : "N/A");
    try {
      // ── Run the agentic loop ───────────────────────────────────────────
      AiAnalysisDocument analysis = runAgentLoop(document);

      log.info("[ExceptionAnalysisAgent] Agentic analysis complete — severity='{}' component='{}'",
              analysis.getSeverity(), analysis.getAffectedComponent());

      // ── Always send email ──────────────────────────────────────────────
      notificationService.sendExceptionAlert(document, analysis);

    } catch (Exception e) {
      log.error("[ExceptionAnalysisAgent] Agent failed — id='{}' error='{}'",
              document.getId(), e.getMessage(), e);
    }
  }

  // ── Agentic Loop ───────────────────────────────────────────────────────────

  private AiAnalysisDocument runAgentLoop(ExceptionDocument document) throws Exception {

    // Build the tool definitions to send to the model
    ArrayNode tools = buildToolDefinitions();

    // Conversation history — grows with each turn
    List<ObjectNode> messages = new ArrayList<>();

    // Turn 0: system message
    ObjectNode systemMsg = objectMapper.createObjectNode();
    systemMsg.put("role", "system");
    systemMsg.put("content", buildSystemPrompt());
    messages.add(systemMsg);

    // Turn 1: user message with exception context
    ObjectNode userMsg = objectMapper.createObjectNode();
    userMsg.put("role", "user");
    userMsg.put("content", buildInitialUserMessage(document));
    messages.add(userMsg);

    // ── Loop ──────────────────────────────────────────────────────────────
    for (int turn = 1; turn <= MAX_TURNS; turn++) {
      log.info("[ExceptionAnalysisAgent] Agent turn {}/{}", turn, MAX_TURNS);

      // Call the model
      JsonNode response = callGroqApi(messages, tools);
      JsonNode choice   = response.path("choices").get(0);
      JsonNode message  = choice.path("message");

      String finishReason = choice.path("finish_reason").asText("");

      // Add assistant response to conversation history
      ObjectNode assistantMsg = objectMapper.createObjectNode();
      assistantMsg.put("role", "assistant");

      // ── Case 1: Model wants to call a tool ────────────────────────────
      if ("tool_calls".equals(finishReason) || message.has("tool_calls")) {
        JsonNode toolCalls = message.path("tool_calls");
        assistantMsg.set("tool_calls", toolCalls);
        // content may be null for tool_calls — set to null node
        if (message.has("content") && !message.path("content").isNull()) {
          assistantMsg.put("content", message.path("content").asText(""));
        } else {
          assistantMsg.putNull("content");
        }
        messages.add(assistantMsg);

        // Execute each tool call and add results to conversation
        for (JsonNode toolCall : toolCalls) {
          String toolCallId   = toolCall.path("id").asText();
          String toolName     = toolCall.path("function").path("name").asText();
          String toolArgsJson = toolCall.path("function").path("arguments").asText();

          log.info("[ExceptionAnalysisAgent] Agent calling tool='{}' args='{}'",
                  toolName, toolArgsJson);

          String toolResult = executeTool(toolName, toolArgsJson);

          log.info("[ExceptionAnalysisAgent] Tool '{}' returned {} chars",
                  toolName, toolResult.length());

          // Add tool result to conversation
          ObjectNode toolResultMsg = objectMapper.createObjectNode();
          toolResultMsg.put("role",         "tool");
          toolResultMsg.put("tool_call_id", toolCallId);
          toolResultMsg.put("content",      toolResult);
          messages.add(toolResultMsg);
        }
        // Continue loop — model will reason over tool results
        continue;
      }

      // ── Case 2: Model produced final answer ───────────────────────────
      String finalText = message.path("content").asText("");

      if (finalText.isBlank()) {
        throw new RuntimeException("Agent produced empty final response on turn " + turn);
      }

      log.info("[ExceptionAnalysisAgent] Agent produced final answer on turn {}", turn);
      return parseAgentFinalResponse(finalText);
    }

    throw new RuntimeException("Agent exceeded MAX_TURNS (" + MAX_TURNS + ") without producing final answer");
  }

  // ── Tool Execution ─────────────────────────────────────────────────────────

  private String executeTool(String toolName, String toolArgsJson) {
    try {
      JsonNode args = objectMapper.readTree(toolArgsJson);
      String   originalMessageId = args.path("originalMessageId").asText();

      return switch (toolName) {
        case TOOL_AUDIT_HISTORY    -> agentToolService.checkAuditHistory(originalMessageId);
        case TOOL_EXCEPTION_DETAIL -> agentToolService.checkExceptionDetail(originalMessageId);
        default -> "{\"error\": \"Unknown tool: " + toolName + "\"}";
      };
    } catch (Exception e) {
      return "{\"error\": \"Tool execution failed: " + e.getMessage() + "\"}";
    }
  }

  // ── Prompt Builders ────────────────────────────────────────────────────────

  private String buildSystemPrompt() {
    return """
                You are an expert integration platform diagnostic agent for an Apache Camel \
                message routing system used in financial services.

                Your job is to analyse exceptions that occur during message routing and produce \
                a complete diagnosis including:
                  1. The full message journey — every route leg from source queue to destination
                  2. Exactly where and why the exception occurred
                  3. A clear, actionable fix suggestion for the development or operations team

                You have access to two tools:
                  - checkAuditHistory: retrieves all route legs the message completed successfully
                  - checkExceptionDetail: retrieves the full exception record for the failed leg

                Always call BOTH tools before producing your final answer. You need the audit \
                history to reconstruct the successful legs of the journey, and the exception \
                detail to understand the failure.

                When you have called both tools and have the full picture, respond ONLY with \
                a JSON object. Do not include any explanation, markdown, or code fences.

                Respond with exactly this structure:
                {
                  "messageJourney": [
                    {
                      "routeName": "Route1",
                      "source": "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
                      "target": "CORE.ENTRY.SERVICE.IN",
                      "status": "SUCCESS",
                      "startTimestamp": "...",
                      "endTimestamp": "..."
                    },
                    {
                      "routeName": "Route2",
                      "source": "CORE.ENTRY.SERVICE.IN",
                      "target": "GATEWAY.EXIT.WW.SCENARIO1.1.OUT",
                      "status": "FAILED",
                      "startTimestamp": "...",
                      "endTimestamp": ""
                    }
                  ],
                  "summary": "Plain English explanation of what went wrong",
                  "rootCause": "The specific technical reason this exception occurred",
                  "affectedComponent": "The exact class or method that threw the exception",
                  "suggestedFix": "Concrete, actionable steps to resolve this",
                  "severity": "LOW, MEDIUM, HIGH, or CRITICAL"
                }

                Severity guide:
                  LOW      — expected/intentional rejection (known validation rule)
                  MEDIUM   — recoverable, retry or config fix sufficient
                  HIGH     — system error, data loss risk
                  CRITICAL — platform-wide failure risk
                """;
  }

  private String buildInitialUserMessage(ExceptionDocument doc) {
    String scenario = doc.getRoutingSlip() != null ? doc.getRoutingSlip().getScenarioName() : "Unknown";
    String country  = doc.getRoutingSlip() != null ? doc.getRoutingSlip().getCountryCode()  : "Unknown";

    return String.format("""
                An exception has occurred in the integration platform. Please analyse it.

                Basic Exception Context:
                - Original Message ID : %s
                - Scenario            : %s (%s)
                - Exception Code      : %s
                - Failed at timestamp : %s

                Use your tools to retrieve the full audit history and exception detail \
                for this message, then produce your complete analysis.
                """,
            doc.getOriginalMessageId(),
            scenario, country,
            doc.getExceptionCode(),
            doc.getEventTimestamp()
    );
  }

  // ── Tool Definitions (Groq/OpenAI function calling format) ─────────────────

  private ArrayNode buildToolDefinitions() throws Exception {
    String toolsJson = """
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "checkAuditHistory",
                      "description": "Retrieves all route legs that the message completed successfully before the exception occurred. Returns a chronological list of audit records from MongoDB showing the message journey. Call this first to understand which legs succeeded.",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "originalMessageId": {
                            "type": "string",
                            "description": "The OriginalMessageId that uniquely identifies the message across all route legs"
                          }
                        },
                        "required": ["originalMessageId"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "checkExceptionDetail",
                      "description": "Retrieves the full exception record for the message from MongoDB, including the failed route leg, exception code, full stack trace, and the message payload that caused the failure. Call this to understand why the message failed.",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "originalMessageId": {
                            "type": "string",
                            "description": "The OriginalMessageId that uniquely identifies the message across all route legs"
                          }
                        },
                        "required": ["originalMessageId"]
                      }
                    }
                  }
                ]
                """;
    return (ArrayNode) objectMapper.readTree(toolsJson);
  }

  // ── Groq API Call ──────────────────────────────────────────────────────────

  private JsonNode callGroqApi(List<ObjectNode> messages, ArrayNode tools) throws Exception {

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model",      model);
    requestBody.put("max_tokens", maxTokens);

    ArrayNode messagesArray = requestBody.putArray("messages");
    messages.forEach(messagesArray::add);
    requestBody.set("tools", tools);
    requestBody.put("tool_choice", "auto");

    String requestJson = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(TIMEOUT)
            .header("Content-Type",  "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .build();

    HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    HttpResponse<String> response = client.send(request,
            HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException(
              "Groq API returned status " + response.statusCode() +
                      " — body: " + response.body());
    }

    return objectMapper.readTree(response.body());
  }

  // ── Final Response Parser ──────────────────────────────────────────────────

  private AiAnalysisDocument parseAgentFinalResponse(String rawText) throws Exception {

    String cleaned = rawText.replaceAll("(?s)```json\\s*", "")
            .replaceAll("(?s)```\\s*",     "")
            .trim();

    JsonNode root = objectMapper.readTree(cleaned);

    // Build the journey narrative string from the messageJourney array
    StringBuilder journeyBuilder = new StringBuilder();
    JsonNode journeyArr = root.path("messageJourney");
    if (journeyArr.isArray()) {
      for (JsonNode leg : journeyArr) {
        String status = leg.path("status").asText("UNKNOWN");
        String icon   = "SUCCESS".equals(status) ? "✅" : "❌";
        journeyBuilder.append(String.format("%s %s  %s → %s%n",
                icon,
                leg.path("routeName").asText(""),
                leg.path("source").asText(""),
                leg.path("target").asText("")));
      }
    }

    // Store journey in summary so NotificationService can render it
    String journeyNarrative = journeyBuilder.toString().trim();
    String summary          = root.path("summary").asText("N/A");
    String fullSummary      = journeyNarrative.isEmpty()
            ? summary
            : "MESSAGE JOURNEY:\n" + journeyNarrative + "\n\nSUMMARY:\n" + summary;

    return AiAnalysisDocument.builder()
            .summary          (fullSummary)
            .rootCause        (root.path("rootCause").asText("N/A"))
            .affectedComponent(root.path("affectedComponent").asText("N/A"))
            .suggestedAction  (root.path("suggestedFix").asText("N/A"))
            .severity         (root.path("severity").asText("MEDIUM"))
            .analysedAt       (Instant.now())
            .build();
  }
}