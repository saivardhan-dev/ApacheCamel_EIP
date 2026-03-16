package com.apachecamel.mini_integration_platform.service.persistence;

import com.apachecamel.mini_integration_platform.model.document.ExceptionDocument;
import com.apachecamel.mini_integration_platform.model.document.RouteInfoDocument;
import com.apachecamel.mini_integration_platform.model.document.RoutingSlipDocument;
import com.apachecamel.mini_integration_platform.repository.ExceptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * ExceptionPersistenceService
 *
 * Called by ExceptionRoute when a message arrives on COMMON.EXCEPTION.SERVICE.IN.
 *
 * Responsibility:
 *   1. Parse the raw Exception JSON string from the queue
 *   2. Map it to an ExceptionDocument
 *   3. Save it to the MongoDB "exceptions" collection via ExceptionRepository
 *
 * Input JSON shape (from ExceptionProcessor):
 * {
 *   "OriginalMessageId":   "...",
 *   "SourcePutTimestamp":  "...",
 *   "EventTimestamp":      "...",
 *   "MessageId":           "...",
 *   "Headers": {
 *     "RoutingSlip": { "CountryCode":"WW", "ScenarioName":"Scenario1", "InstanceId":1 },
 *     "RouteInfo":   { "RouteName":"Route2", "source":"...", "target":"...",
 *                      "startTimestamp":"...", "endTimestamp":"" }
 *   },
 *   "Payload":              "...",
 *   "ExceptionCode":        "ExceptionCode3",
 *   "ExceptionStacktrace":  "java.lang.RuntimeException: ..."
 * }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionPersistenceService {

    private final ExceptionRepository exceptionRepository;
    private final ObjectMapper        objectMapper;

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses the raw exception JSON and persists it to MongoDB.
     * Returns the saved document (with MongoDB _id populated) so the
     * caller can pass it to ExceptionAnalysisService for AI analysis.
     *
     * @param exceptionJson  the raw JSON string consumed from COMMON.EXCEPTION.SERVICE.IN
     * @return               the saved ExceptionDocument with _id populated, or null on failure
     */
    public ExceptionDocument save(String exceptionJson) {
        try {
            JsonNode root = objectMapper.readTree(exceptionJson);

            // ── Parse Headers block ────────────────────────────────────────────
            JsonNode headers     = root.path("Headers");
            JsonNode routingSlip = headers.path("RoutingSlip");
            JsonNode routeInfo   = headers.path("RouteInfo");

            // ── Build embedded RoutingSlipDocument ────────────────────────────
            RoutingSlipDocument routingSlipDoc = RoutingSlipDocument.builder()
                    .countryCode (routingSlip.path("CountryCode").asText(null))
                    .scenarioName(routingSlip.path("ScenarioName").asText(null))
                    .instanceId  (routingSlip.path("InstanceId").asInt(0))
                    .build();

            // ── Build embedded RouteInfoDocument ──────────────────────────────
            RouteInfoDocument routeInfoDoc = RouteInfoDocument.builder()
                    .routeName      (routeInfo.path("RouteName").asText(null))
                    .source         (routeInfo.path("source").asText(null))
                    .target         (routeInfo.path("target").asText(null))
                    .startTimestamp (routeInfo.path("startTimestamp").asText(null))
                    .endTimestamp   (routeInfo.path("endTimestamp").asText(""))  // always "" for exceptions
                    .build();

            // ── Build ExceptionDocument ───────────────────────────────────────
            ExceptionDocument document = ExceptionDocument.builder()
                    .originalMessageId  (root.path("OriginalMessageId").asText(null))
                    .sourcePutTimestamp (root.path("SourcePutTimestamp").asText(null))
                    .eventTimestamp     (root.path("EventTimestamp").asText(null))
                    .messageId          (root.path("MessageId").asText(null))
                    .routingSlip        (routingSlipDoc)
                    .routeInfo          (routeInfoDoc)
                    .payload            (root.path("Payload").asText(null))
                    .exceptionCode      (root.path("ExceptionCode").asText(null))
                    .exceptionStacktrace(root.path("ExceptionStacktrace").asText(null))
                    .createdAt          (Instant.now())
                    .build();

            // ── Save to MongoDB "exceptions" collection ───────────────────────
            ExceptionDocument saved = exceptionRepository.save(document);

            log.error("[ExceptionPersistenceService] Saved exception — id='{}' msgId='{}' code='{}' route='{}'",
                    saved.getId(),
                    saved.getOriginalMessageId(),
                    saved.getExceptionCode(),
                    saved.getRouteInfo() != null ? saved.getRouteInfo().getRouteName() : "N/A");

            return saved;

        } catch (Exception e) {
            log.error("[ExceptionPersistenceService] Failed to parse/save exception JSON — {}",
                    e.getMessage(), e);
            return null;
        }
    }
}