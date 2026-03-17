package com.apachecamel.mini_integration_platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static com.apachecamel.mini_integration_platform.processor.ScenarioProcessor.*;

/**
 * AuditJsonBuilder
 *
 * Builds Audit JSON strings from an exchange.
 *
 * Two audit types are supported — both have SourcePutTimestamp AND EventTimestamp:
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  ENTRY audit — fired when message ENTERS a route                    │
 * │    sourcePutTimestamp : ✅ populated                                 │
 * │    eventTimestamp     : ✅ Instant.now() (time of entry)            │
 * │    auditType          : "ENTRY"                                     │
 * │                                                                     │
 * │  EXIT audit — fired when message LEAVES a route                     │
 * │    sourcePutTimestamp : ✅ populated                                 │
 * │    eventTimestamp     : ✅ Instant.now() (time of exit)             │
 * │    auditType          : "EXIT"                                      │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * The auditType field is the only distinction between the two documents.
 * Comparing ENTRY eventTimestamp vs EXIT eventTimestamp gives you the
 * processing time for each route leg.
 *
 * Per message, per happy path:
 *   Route1 ENTRY  → auditType=ENTRY, eventTimestamp=<entry time>
 *   Route1 EXIT   → auditType=EXIT,  eventTimestamp=<exit time>
 *   Route2 ENTRY  → auditType=ENTRY, eventTimestamp=<entry time>
 *   Route2 EXIT   → auditType=EXIT,  eventTimestamp=<exit time>
 *   Total: 4 audit documents in MongoDB
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditJsonBuilder {

  private final ObjectMapper objectMapper;

  /** Audit type constants */
  public static final String AUDIT_TYPE_ENTRY = "ENTRY";
  public static final String AUDIT_TYPE_EXIT  = "EXIT";

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Builds an ENTRY audit JSON — eventTimestamp is empty.
   * Convenience method — delegates to build(exchange, ENTRY).
   */
  public String buildEntry(Exchange exchange) {
    return build(exchange, AUDIT_TYPE_ENTRY);
  }

  /**
   * Builds an EXIT audit JSON — eventTimestamp is Instant.now().
   * Convenience method — delegates to build(exchange, EXIT).
   */
  public String buildExit(Exchange exchange) {
    return build(exchange, AUDIT_TYPE_EXIT);
  }

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Core build method — builds an Audit JSON string for the given audit type.
   *
   * @param exchange   the exchange to read headers and body from
   * @param auditType  ENTRY or EXIT — controls eventTimestamp population
   * @return           Audit JSON string, or null on failure
   */
  public String build(Exchange exchange, String auditType) {
    try {
      String originalMessageId  = exchange.getIn().getHeader(ORIGINAL_MESSAGE_ID,  String.class);
      String sourcePutTimestamp = exchange.getIn().getHeader(SOURCE_PUT_TIMESTAMP,  String.class);
      String messageId          = exchange.getIn().getMessageId();
      String countryCode        = exchange.getIn().getHeader(ROUTING_SLIP_COUNTRY,  String.class);
      String scenarioName       = exchange.getIn().getHeader(ROUTING_SLIP_SCENARIO, String.class);
      int    instanceId         = exchange.getIn().getHeader(ROUTING_SLIP_INSTANCE, 0, Integer.class);
      String scenarioSrcQueue   = exchange.getIn().getHeader(SCENARIO_SOURCE_QUEUE, String.class);
      String routeName          = exchange.getIn().getHeader(CURRENT_ROUTE_NAME,    String.class);
      String routeSource        = exchange.getIn().getHeader(ROUTE_SOURCE,          String.class);
      String routeTarget        = exchange.getIn().getHeader(ROUTE_TARGET,          String.class);
      String startTimestamp     = exchange.getIn().getHeader(ROUTE_START_TIMESTAMP, String.class);
      String payload            = exchange.getIn().getBody(String.class);

      // eventTimestamp — populated for BOTH ENTRY and EXIT
      // The auditType field ("ENTRY" / "EXIT") is what distinguishes them
      String eventTimestamp = Instant.now().toString();

      // endTimestamp in routeInfo — populated for BOTH ENTRY and EXIT
      String endTimestamp = Instant.now().toString();

      ObjectNode auditNode = objectMapper.createObjectNode();
      auditNode.put("OriginalMessageId",  originalMessageId);
      auditNode.put("SourcePutTimestamp", sourcePutTimestamp);
      auditNode.put("EventTimestamp",     eventTimestamp);
      auditNode.put("MessageId",          messageId);
      auditNode.put("AuditType",          auditType);   // ENTRY or EXIT

      ObjectNode headersNode = auditNode.putObject("Headers");
      headersNode.put("auditQueue", scenarioSrcQueue);

      ObjectNode routingSlip = headersNode.putObject("RoutingSlip");
      routingSlip.put("CountryCode",  countryCode);
      routingSlip.put("ScenarioName", scenarioName);
      routingSlip.put("InstanceId",   instanceId);

      ObjectNode routeInfo = headersNode.putObject("RouteInfo");
      routeInfo.put("RouteName",      routeName);
      routeInfo.put("source",         routeSource);
      routeInfo.put("target",         routeTarget);
      routeInfo.put("startTimestamp", startTimestamp);
      routeInfo.put("endTimestamp",   endTimestamp);

      auditNode.put("Payload", payload);

      log.debug("[AuditJsonBuilder] Built {} audit — msgId='{}' route='{}'",
              auditType, originalMessageId, routeName);

      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(auditNode);

    } catch (Exception e) {
      log.error("[AuditJsonBuilder] Failed to build audit JSON — {}", e.getMessage(), e);
      return null;
    }
  }
}