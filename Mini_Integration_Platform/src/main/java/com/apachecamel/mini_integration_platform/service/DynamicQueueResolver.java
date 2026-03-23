package com.apachecamel.mini_integration_platform.service;

import com.apachecamel.mini_integration_platform.model.CBRRule;
import com.apachecamel.mini_integration_platform.model.MessagePayload;
import com.apachecamel.mini_integration_platform.model.ServiceConfig;
import com.apachecamel.mini_integration_platform.processor.MessageSplitterProcessor;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import static com.apachecamel.mini_integration_platform.processor.MessageValidatorProcessor.PROP_MESSAGE_PAYLOAD;
import static com.apachecamel.mini_integration_platform.processor.ScenarioProcessor.ROUTING_SLIP_SCENARIO;

/**
 * DynamicQueueResolver
 *
 * Generic CBR rule engine. Evaluates CBRRules against the exchange payload
 * and resolves the target queue name.
 *
 * leftOperand field resolution — two modes detected automatically:
 *
 *   leftOperand starts with "$" → JSONPath expression
 *     → evaluated against the original envelope body
 *     → e.g. "$.Messages.CarDetails.Model" → "Cars"
 *
 *   leftOperand does NOT start with "$" → plain field name
 *     → read from top-level MessagePayload map
 *     → e.g. "type" → payload.getString("type") → "Cars"
 *
 * Examples in Scenarios.json:
 *
 *   JSONPath:    "leftOperand": "$.Messages.CarDetails.Model"
 *   Plain field: "leftOperand": "type"
 *
 * Zero Java changes needed when payload structure changes —
 * only leftOperand in Scenarios.json needs updating.
 */
@Slf4j
@Service
public class DynamicQueueResolver {

  private static final String PLACEHOLDER_SCENARIO = "{SCENARIO}";

  // ──────────────────────────────────────────────────────────────────────────

  public String resolve(ServiceConfig serviceConfig, Exchange exchange) {

    MessagePayload payload = exchange.getProperty(PROP_MESSAGE_PAYLOAD,
            MessagePayload.class);
    if (payload == null) {
      throw new RuntimeException(
              "MessagePayload not found on exchange — " +
                      "MessageValidatorProcessor must run before DynamicQueueResolver"
      );
    }

    // ── Raw body for JSONPath evaluation ───────────────────────────────────
    // Three modes — which body to use for JSONPath:
    //
    //   UNWRAP mode → use OriginalEnvelopeBody header (full envelope)
    //     Body was replaced with inner object. JSONPath paths like
    //     $.Messages.CarDetails.Model need the full envelope.
    //
    //   SPLIT mode → use current body (individual split item)
    //     Body is one split item e.g. { "CarDetails": { ... } }
    //     OriginalEnvelopeBody is an array — JSONPath cannot address
    //     individual items without index. Use current body instead.
    //     Rules should reference paths relative to the split item:
    //     e.g. "$.CarDetails.Model" not "$.Messages.CarDetails.Model"
    //
    //   PASSTHROUGH → use current body (plain single message)
    //
    String originalBody = exchange.getIn().getHeader(
            MessageSplitterProcessor.PROP_ORIGINAL_BODY, String.class);
    Boolean isSplit = exchange.getIn().getHeader(
            MessageSplitterProcessor.PROP_IS_SPLIT, Boolean.class);

    // All three modes evaluate JSONPath against the CURRENT body:
    //   SPLIT      → current body is the split item  { "CarDetails": {...} }
    //   UNWRAP     → current body is unwrapped object { "CarDetails": {...} }
    //   PASSTHROUGH→ current body is the message itself
    //
    // leftOperand paths in Scenarios.json are always relative to the
    // current body — e.g. "$.CarDetails.Model" works for all three modes.
    // The OriginalEnvelopeBody header is no longer needed for routing.
    String rawBody = exchange.getIn().getBody(String.class);

    log.debug("[DynamicQueueResolver] JSONPath evaluating against current body, " +
            "isSplit={}", isSplit);

    String scenario = readScenario(exchange);
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put(PLACEHOLDER_SCENARIO, scenario);

    // ── Evaluate each rule ─────────────────────────────────────────────────
    for (CBRRule rule : serviceConfig.getRules()) {
      if (rule.isStringRule()) {
        evaluateStringRule(rule, payload, rawBody, placeholders);

      } else if (rule.isDoubleRule()) {
        evaluateDoubleRule(rule, payload, rawBody);

      } else if (rule.isDoubleRangeRule()) {
        evaluateDoubleRangeRule(rule, payload, rawBody, placeholders);

      } else if (rule.isDoubleGtRule()) {
        evaluateDoubleGtRule(rule, payload, rawBody, placeholders);

      } else {
        log.warn("[DynamicQueueResolver] Unknown comparatorType '{}' — skipping",
                rule.getComparatorType());
      }
    }

    // ── Substitute placeholders into queue pattern ─────────────────────────
    String queueName = serviceConfig.getQueuePattern();
    for (Map.Entry<String, String> entry : placeholders.entrySet()) {
      queueName = queueName.replace(entry.getKey(), entry.getValue());
    }

    // ── Unresolved placeholders → return null → DLQ ───────────────────────
    if (queueName.contains("{") && queueName.contains("}")) {
      log.warn("[DynamicQueueResolver] Unresolved placeholders '{}' — returning null for DLQ",
              queueName);
      return null;
    }

    log.info("[DynamicQueueResolver] Resolved — scenario='{}' queue='{}'",
            scenario, queueName);
    return queueName;
  }

  // ── Field resolution — $ prefix detection ─────────────────────────────────

  /**
   * Reads a String value from the payload.
   *
   * If leftOperand starts with "$" → JSONPath expression
   *   JsonPath.read(rawBody, "$.Messages.CarDetails.Model") → "Cars"
   *
   * Otherwise → plain field name
   *   payload.getString("type") → "Cars"
   */
  private String resolveStringField(String leftOperand,
                                    MessagePayload payload,
                                    String rawBody) {
    if (isJsonPath(leftOperand)) {
      try {
        Object value = JsonPath.read(rawBody, leftOperand);
        String result = value != null ? value.toString() : null;
        log.debug("[DynamicQueueResolver] JSONPath '{}' → '{}'",
                leftOperand, result);
        return result;
      } catch (PathNotFoundException e) {
        log.debug("[DynamicQueueResolver] JSONPath '{}' not found in body",
                leftOperand);
        return null;
      }
    }
    return payload.getString(leftOperand);
  }

  /**
   * Reads a Double value from the payload.
   *
   * If leftOperand starts with "$" → JSONPath expression
   *   JsonPath.read(rawBody, "$.Messages.CarDetails.Price") → 8000.0
   *
   * Otherwise → plain field name
   *   payload.getDouble("amount") → 8000.0
   */
  private Double resolveDoubleField(String leftOperand,
                                    MessagePayload payload,
                                    String rawBody) {
    if (isJsonPath(leftOperand)) {
      try {
        Object value = JsonPath.read(rawBody, leftOperand);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
      } catch (PathNotFoundException e) {
        log.debug("[DynamicQueueResolver] JSONPath '{}' not found in body",
                leftOperand);
        return null;
      } catch (NumberFormatException e) {
        throw new NumberFormatException(
                "JSONPath '" + leftOperand + "' value is not numeric — " +
                        e.getMessage()
        );
      }
    }
    return payload.getDouble(leftOperand);
  }

  /**
   * Detects whether leftOperand is a JSONPath expression.
   * JSONPath expressions always start with "$".
   */
  private boolean isJsonPath(String leftOperand) {
    return leftOperand != null && leftOperand.startsWith("$");
  }

  // ── Rule evaluators ────────────────────────────────────────────────────────

  private void evaluateStringRule(CBRRule rule, MessagePayload payload,
                                  String rawBody,
                                  Map<String, String> placeholders) {
    String leftOperand = rule.getLeftOperand();

    if (isJsonPath(leftOperand)) {
      // ── JSONPath mode ──────────────────────────────────────────────────
      // Step 1: Validate the path actually resolves a value in the message.
      //   If the path does not exist → message does not match this rule
      //   → throw exception → DLQ.
      //   This ensures wrong paths or wrong message structures fail fast.
      //
      // Step 2: If path resolves → extract parent key as {TYPE}.
      //   e.g. "$.Messages.ElectronicsDetails.DeviceName"
      //     → path exists in message    → parent = "ElectronicsDetails"
      //     → {TYPE} = "ELECTRONICSDETAILS"
      //   e.g. "$.Messages.ElectronicsDetails.DeviceName"
      //     → path NOT in message (has "Model" not "DeviceName")
      //     → throw RuntimeException → DLQ

      // Step 1 — validate path resolves
      Object resolvedValue;
      try {
        resolvedValue = JsonPath.read(rawBody, leftOperand);
      } catch (PathNotFoundException e) {
        resolvedValue = null;
      }

      if (resolvedValue == null) {
        throw new RuntimeException(
                "JSONPath '" + leftOperand + "' not found in message payload. " +
                        "Message structure does not match the configured path. " +
                        "Check your message or update leftOperand in Scenarios.json."
        );
      }

      // Step 2 — extract parent key name as {TYPE}
      String[] segments = leftOperand.split("\\.");
      if (segments.length < 2) {
        log.warn("[DynamicQueueResolver] JSONPath '{}' too short to extract parent key — skipping",
                leftOperand);
        return;
      }
      String parentKey = segments[segments.length - 2];

      placeholders.put(rule.getRightOperand(), parentKey.toUpperCase());
      log.info("[DynamicQueueResolver] STRING JSONPath '{}' resolved → " +
                      "parent key '{}' → placeholder '{}'='{}'",
              leftOperand, parentKey, rule.getRightOperand(), parentKey.toUpperCase());

    } else {
      // ── Plain field mode — use field value as placeholder value ─────────
      // e.g. leftOperand = "type", payload has type="Cars"
      //   → {TYPE} = "CARS"
      String value = resolveStringField(leftOperand, payload, rawBody);

      if (value == null || value.isBlank()) {
        log.info("[DynamicQueueResolver] STRING '{}' not found — skipping",
                leftOperand);
        return;
      }

      placeholders.put(rule.getRightOperand(), value.trim().toUpperCase());
      log.debug("[DynamicQueueResolver] STRING '{}' = '{}' → placeholder '{}'='{}'",
              leftOperand, value, rule.getRightOperand(), value.trim().toUpperCase());
    }
  }

  private void evaluateDoubleRule(CBRRule rule, MessagePayload payload,
                                  String rawBody) {
    String leftOperand = rule.getLeftOperand();
    Double value       = resolveDoubleField(leftOperand, payload, rawBody);

    if (value == null) {
      log.info("[DynamicQueueResolver] DOUBLE '{}' not found — skipping",
              leftOperand);
      return;
    }

    double  threshold   = rule.getThreshold();
    boolean conditionMet = evaluateCondition(value, rule.getOperationType(),
            threshold);

    log.debug("[DynamicQueueResolver] DOUBLE '{}' = {} {} {} → {}",
            leftOperand, value, rule.getOperationType(), threshold,
            conditionMet ? "MATCH" : "NO MATCH");

    if (conditionMet && "EXCEPTION".equalsIgnoreCase(rule.getOnFailure())) {
      throw new RuntimeException(
              buildFailureMessage(rule.getFailureMessage(),
                      leftOperand, value, threshold)
      );
    }
  }

  private void evaluateDoubleRangeRule(CBRRule rule, MessagePayload payload,
                                       String rawBody,
                                       Map<String, String> placeholders) {
    String leftOperand = rule.getLeftOperand();
    Double value       = resolveDoubleField(leftOperand, payload, rawBody);

    if (value == null) {
      log.info("[DynamicQueueResolver] DOUBLE_RANGE '{}' not found — skipping",
              leftOperand);
      return;
    }

    double lower = rule.getLowerBound();
    double upper = rule.getUpperBound();

    if (value >= lower && value <= upper) {
      placeholders.put(rule.getRightOperand(), rule.getRangeValue());
      log.info("[DynamicQueueResolver] DOUBLE_RANGE '{}' = {} in [{},{}] → '{}'",
              leftOperand, value, lower, upper, rule.getRangeValue());
    }
  }

  private void evaluateDoubleGtRule(CBRRule rule, MessagePayload payload,
                                    String rawBody,
                                    Map<String, String> placeholders) {
    String leftOperand = rule.getLeftOperand();
    Double value       = resolveDoubleField(leftOperand, payload, rawBody);

    if (value == null) {
      log.info("[DynamicQueueResolver] DOUBLE_GT '{}' not found — skipping",
              leftOperand);
      return;
    }

    if (value > rule.getThreshold()) {
      placeholders.put(rule.getRightOperand(), rule.getRangeValue());
      log.info("[DynamicQueueResolver] DOUBLE_GT '{}' = {} > {} → '{}'",
              leftOperand, value, rule.getThreshold(), rule.getRangeValue());
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private boolean evaluateCondition(double actual, String operationType,
                                    double threshold) {
    return switch (operationType.toUpperCase()) {
      case "GTE" -> actual >= threshold;
      case "GT"  -> actual >  threshold;
      case "LTE" -> actual <= threshold;
      case "LT"  -> actual <  threshold;
      case "EQ"  -> actual == threshold;
      case "NEQ" -> actual != threshold;
      default -> throw new RuntimeException(
              "Unknown operationType '" + operationType + "'"
      );
    };
  }

  private String buildFailureMessage(String template, String leftOperand,
                                     double actualValue, double threshold) {
    if (template == null || template.isBlank()) {
      return "CBR rule failed — " + leftOperand + "=" + actualValue +
              " does not satisfy threshold " + threshold;
    }
    return template
            .replace("{leftOperand}", String.valueOf(actualValue))
            .replace("{threshold}",   String.valueOf(threshold));
  }

  private String readScenario(Exchange exchange) {
    String raw = exchange.getIn().getHeader(ROUTING_SLIP_SCENARIO, String.class);
    return (raw != null) ? raw.toUpperCase().replace(" ", "") : "UNKNOWN";
  }
}