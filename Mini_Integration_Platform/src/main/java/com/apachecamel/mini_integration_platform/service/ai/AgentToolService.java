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
import java.util.stream.Collectors;

/**
 * AgentToolService
 *
 * Implements the two tools available to the ExceptionAnalysisAgent.
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Tool 1: checkAuditHistory(originalMessageId)                       │
 * │    → Queries MongoDB "audits" collection                            │
 * │    → Returns only EXIT audit records (one per successful route leg) │
 * │    → ENTRY audits filtered out — agent sees one clean path entry    │
 * │    → Sorted chronologically by startTimestamp                       │
 * │                                                                     │
 * │  Tool 2: checkExceptionDetail(originalMessageId)                    │
 * │    → Queries MongoDB "exceptions" collection                        │
 * │    → Returns the exception record for the failed leg                │
 * │    → Includes exceptionCode, stacktrace, routeInfo                  │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Why EXIT only for audit history:
 *   Each route leg produces 2 audit documents — ENTRY and EXIT.
 *   ENTRY = message arrived at the leg (no endTimestamp yet)
 *   EXIT  = message left the leg successfully (endTimestamp populated)
 *
 *   The agent only needs EXIT records to reconstruct the journey —
 *   EXIT confirms the leg completed. Showing ENTRY too would give
 *   Route1 twice in the journey narrative which is misleading.
 *
 *   Result for a message that completed Route1 and failed at Route2:
 *     checkAuditHistory → [ { Route1, EXIT, SUCCESS } ]
 *     checkExceptionDetail → { Route2, FAILED, ExceptionCode2 }
 *
 *   The agent sees: Route1 succeeded, Route2 failed. Clean and correct.
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
   * Returns only EXIT audit records for the given message.
   * One record per successfully completed route leg.
   *
   * ENTRY records are filtered out so the agent sees a clean
   * one-entry-per-leg journey — not two entries per leg.
   *
   * @param originalMessageId  the OriginalMessageId header stamped by ScenarioProcessor
   * @return JSON array of EXIT audit legs in chronological order
   */
  public String checkAuditHistory(String originalMessageId) {
    log.info("[AgentToolService] checkAuditHistory — originalMessageId='{}'", originalMessageId);
    try {
      List<AuditDocument> allAudits = auditRepository
              .findByOriginalMessageId(originalMessageId);

      // ── Filter: EXIT audits only ───────────────────────────────────────
      // EXIT = message successfully left the route leg
      // ENTRY = message arrived but may not have completed — excluded
      List<AuditDocument> exitAudits = allAudits.stream()
              .filter(a -> "EXIT".equalsIgnoreCase(a.getAuditType()))
              .collect(Collectors.toList());

      // ── Sort chronologically ───────────────────────────────────────────
      exitAudits.sort(Comparator.comparing(
              a -> a.getRouteInfo() != null ? a.getRouteInfo().getStartTimestamp() : "",
              Comparator.nullsFirst(Comparator.naturalOrder())
      ));

      ArrayNode result = objectMapper.createArrayNode();

      for (AuditDocument audit : exitAudits) {
        ObjectNode leg = objectMapper.createObjectNode();
        leg.put("routeName",      safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getRouteName()      : null));
        leg.put("source",         safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getSource()         : null));
        leg.put("target",         safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getTarget()         : null));
        leg.put("startTimestamp", safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getStartTimestamp() : null));
        leg.put("endTimestamp",   safe(audit.getRouteInfo() != null ? audit.getRouteInfo().getEndTimestamp()   : null));
        leg.put("auditType",      "EXIT");
        leg.put("status",         "SUCCESS");
        result.add(leg);
      }

      log.info("[AgentToolService] checkAuditHistory — found {} EXIT leg(s) " +
                      "(filtered from {} total audit records)",
              exitAudits.size(), allAudits.size());

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
   * @param originalMessageId  the OriginalMessageId header stamped by ScenarioProcessor
   * @return JSON string of exception detail, or not-found JSON
   */
  public String checkExceptionDetail(String originalMessageId) {
    log.info("[AgentToolService] checkExceptionDetail — originalMessageId='{}'", originalMessageId);
    try {
      List<ExceptionDocument> exceptions = exceptionRepository
              .findByOriginalMessageId(originalMessageId);

      if (exceptions.isEmpty()) {
        return "{\"error\": \"No exception record found for originalMessageId: "
                + originalMessageId + "\"}";
      }

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