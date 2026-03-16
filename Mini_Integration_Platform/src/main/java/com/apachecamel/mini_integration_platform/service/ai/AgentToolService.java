package com.apachecamel.mini_integration_platform.service.ai;

import com.apachecamel.mini_integration_platform.model.document.AuditDocument;
import com.apachecamel.mini_integration_platform.model.document.ExceptionDocument;
import com.apachecamel.mini_integration_platform.repository.AuditRepository;
import com.apachecamel.mini_integration_platform.repository.ExceptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * AgentToolService
 *
 * Implements the two tools available to the ExceptionAnalysisAgent:
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Tool 1: checkAuditHistory(originalMessageId)                       │
 * │    → Queries MongoDB "audits" collection                            │
 * │    → Returns all route legs the message completed successfully      │
 * │    → Sorted by startTimestamp (chronological journey order)         │
 * │                                                                     │
 * │  Tool 2: checkExceptionDetail(originalMessageId)                    │
 * │    → Queries MongoDB "exceptions" collection                        │
 * │    → Returns the exception record for the failed leg                │
 * │    → Includes exceptionCode, stacktrace, routeInfo                  │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Both tools return JSON strings that are fed back into the agent's
 * reasoning loop as tool_result messages.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolService {

  private final AuditRepository     auditRepository;
  private final ExceptionRepository exceptionRepository;
  private final ObjectMapper        objectMapper;

  // ── Tool 1: checkAuditHistory ──────────────────────────────────────────────

  /**
   * Queries the "audits" MongoDB collection for all route legs
   * completed by the message identified by originalMessageId.
   *
   * Returns a JSON string representing the ordered list of audit records:
   * [
   *   {
   *     "routeName":       "Route1",
   *     "source":          "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
   *     "target":          "CORE.ENTRY.SERVICE.IN",
   *     "startTimestamp":  "2026-03-13T14:32:34.324Z",
   *     "endTimestamp":    "2026-03-13T14:32:34.371Z",
   *     "status":          "SUCCESS"
   *   },
   *   ...
   * ]
   *
   * @param originalMessageId  the OriginalMessageId header stamped by ScenarioProcessor
   * @return JSON string of audit legs, or an error JSON if lookup fails
   */
  public String checkAuditHistory(String originalMessageId) {
    log.info("[AgentToolService] checkAuditHistory — originalMessageId='{}'", originalMessageId);
    try {
      List<AuditDocument> audits = auditRepository
              .findByOriginalMessageId(originalMessageId);

      // Sort chronologically by startTimestamp so the agent sees
      // the journey in the correct order
      audits.sort(Comparator.comparing(
              a -> a.getRouteInfo() != null ? a.getRouteInfo().getStartTimestamp() : "",
              Comparator.nullsFirst(Comparator.naturalOrder())
      ));

      ArrayNode result = objectMapper.createArrayNode();

      for (AuditDocument audit : audits) {
        ObjectNode leg = objectMapper.createObjectNode();
        leg.put("routeName",      safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getRouteName()      : null));
        leg.put("source",         safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getSource()         : null));
        leg.put("target",         safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getTarget()         : null));
        leg.put("startTimestamp", safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getStartTimestamp() : null));
        leg.put("endTimestamp",   safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getEndTimestamp()   : null));
        leg.put("status",         "SUCCESS");
        result.add(leg);
      }

      log.info("[AgentToolService] checkAuditHistory — found {} audit leg(s)", audits.size());
      return objectMapper.writeValueAsString(result);

    } catch (Exception e) {
      log.error("[AgentToolService] checkAuditHistory failed — {}", e.getMessage(), e);
      return "{\"error\": \"Failed to retrieve audit history: " + e.getMessage() + "\"}";
    }
  }

  // ── Tool 2: checkExceptionDetail ──────────────────────────────────────────

  /**
   * Queries the "exceptions" MongoDB collection for the exception record
   * associated with the given originalMessageId.
   *
   * Returns a JSON string of the exception detail:
   * {
   *   "routeName":            "Route2",
   *   "source":               "CORE.ENTRY.SERVICE.IN",
   *   "target":               "GATEWAY.EXIT.WW.SCENARIO1.1.OUT",
   *   "exceptionCode":        "ExceptionCode3",
   *   "exceptionStacktrace":  "java.lang.RuntimeException: ...",
   *   "payload":              "{ type: ERROR }",
   *   "eventTimestamp":       "2026-03-13T14:32:34.374Z"
   * }
   *
   * @param originalMessageId  the OriginalMessageId header stamped by ScenarioProcessor
   * @return JSON string of exception detail, or not-found JSON
   */
  public String checkExceptionDetail(String originalMessageId) {
    log.info("[AgentToolService] checkExceptionDetail — originalMessageId='{}'", originalMessageId);
    try {
      List<ExceptionDocument> exceptions = exceptionRepository
              .findByOriginalMessageId(originalMessageId);

      if (exceptions.isEmpty()) {
        return "{\"error\": \"No exception record found for originalMessageId: " + originalMessageId + "\"}";
      }

      // Take the most recent exception (there should only be one per message)
      ExceptionDocument exc = exceptions.get(0);

      ObjectNode result = objectMapper.createObjectNode();
      result.put("routeName",           safe(exc.getRouteInfo() != null ? exc.getRouteInfo().getRouteName() : null));
      result.put("source",              safe(exc.getRouteInfo() != null ? exc.getRouteInfo().getSource()    : null));
      result.put("target",              safe(exc.getRouteInfo() != null ? exc.getRouteInfo().getTarget()    : null));
      result.put("exceptionCode",       safe(exc.getExceptionCode()));
      result.put("exceptionStacktrace", safe(exc.getExceptionStacktrace()));
      result.put("payload",             safe(exc.getPayload()));
      result.put("eventTimestamp",      safe(exc.getEventTimestamp()));

      log.info("[AgentToolService] checkExceptionDetail — found exception code='{}'",
              exc.getExceptionCode());
      return objectMapper.writeValueAsString(result);

    } catch (Exception e) {
      log.error("[AgentToolService] checkExceptionDetail failed — {}", e.getMessage(), e);
      return "{\"error\": \"Failed to retrieve exception detail: " + e.getMessage() + "\"}";
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private String safe(String value) {
    return value != null ? value : "N/A";
  }
}