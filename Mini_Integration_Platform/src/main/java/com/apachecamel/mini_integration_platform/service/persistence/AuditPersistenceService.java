package com.apachecamel.mini_integration_platform.service.persistence;

import com.apachecamel.mini_integration_platform.model.document.AuditDocument;
import com.apachecamel.mini_integration_platform.model.document.RouteInfoDocument;
import com.apachecamel.mini_integration_platform.model.document.RoutingSlipDocument;
import com.apachecamel.mini_integration_platform.repository.AuditRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * AuditPersistenceService
 *
 * Called by AuditRoute when a message arrives on COMMON.AUDIT.SERVICE.IN.
 *
 * Responsibility:
 *   1. Parse the raw Audit JSON string from the queue
 *   2. Map it to an AuditDocument
 *   3. Save it to the MongoDB "audits" collection via AuditRepository
 *
 * Input JSON shape (from AuditProcessor):
 * {
 *   "OriginalMessageId":  "...",
 *   "SourcePutTimestamp": "...",
 *   "EventTimestamp":     "...",
 *   "MessageId":          "...",
 *   "Headers": {
 *     "auditQueue":  "...",
 *     "RoutingSlip": { "CountryCode":"WW", "ScenarioName":"Scenario1", "InstanceId":1 },
 *     "RouteInfo":   { "RouteName":"Route1", "source":"...", "target":"...",
 *                      "startTimestamp":"...", "endTimestamp":"..." }
 *   },
 *   "Payload": "..."
 * }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditPersistenceService {

    private final AuditRepository auditRepository;
    private final ObjectMapper    objectMapper;

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses the raw audit JSON and persists it to MongoDB.
     *
     * @param auditJson  the raw JSON string consumed from COMMON.AUDIT.SERVICE.IN
     */
    public void save(String auditJson) {
        try {
            JsonNode root = objectMapper.readTree(auditJson);

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
                    .endTimestamp   (routeInfo.path("endTimestamp").asText(null))
                    .build();

            // ── Build AuditDocument ───────────────────────────────────────────
            AuditDocument document = AuditDocument.builder()
                    .originalMessageId (root.path("OriginalMessageId").asText(null))
                    .sourcePutTimestamp(root.path("SourcePutTimestamp").asText(null))
                    .eventTimestamp    (root.path("EventTimestamp").asText(null))
                    .messageId         (root.path("MessageId").asText(null))
                    .auditType         (root.path("AuditType").asText(null))
                    .auditQueue        (headers.path("auditQueue").asText(null))
                    .routingSlip       (routingSlipDoc)
                    .routeInfo         (routeInfoDoc)
                    .payload           (root.path("Payload").asText(null))
                    .createdAt         (Instant.now())
                    .build();

            // ── Save to MongoDB "audits" collection ───────────────────────────
            AuditDocument saved = auditRepository.save(document);

            log.info("[AuditPersistenceService] Saved audit to MongoDB — id='{}' " +
                            "originalMessageId='{}' route='{}' auditType='{}'",
                    saved.getId(),
                    saved.getOriginalMessageId(),
                    saved.getRouteInfo() != null ? saved.getRouteInfo().getRouteName() : "N/A",
                    saved.getAuditType());

        } catch (Exception e) {
            log.error("[AuditPersistenceService] Failed to parse/save audit JSON — {}",
                    e.getMessage(), e);
        }
    }
}