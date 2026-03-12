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
 * Single responsibility: build the Audit JSON string from an exchange.
 *
 * Called directly inside .wireTap().onPrepare() in both routes —
 * no separate AuditProcessor step needed.
 *
 * Usage in a route:
 *
 *   .wireTap("activemq:" + auditQueue)
 *       .onPrepare(exchange -> {
 *           String auditJson = auditJsonBuilder.build(exchange);
 *           exchange.getIn().setBody(auditJson);
 *       })
 *   .end()
 *
 * The onPrepare lambda receives the COPY of the exchange going to the
 * audit queue — the main exchange body and headers are untouched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditJsonBuilder {

  private final ObjectMapper objectMapper;

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Builds the Audit JSON string from the current exchange headers and body.
   * Uses Instant.now() as the endTimestamp (time of audit creation).
   *
   * @param exchange  the exchange to read headers and body from
   * @return          Audit JSON string matching Audit.json structure
   */
  public String build(Exchange exchange) {
    try {
      String originalMessageId   = exchange.getIn().getHeader(ORIGINAL_MESSAGE_ID,  String.class);
      String sourcePutTimestamp  = exchange.getIn().getHeader(SOURCE_PUT_TIMESTAMP,  String.class);
      String messageId           = exchange.getIn().getMessageId();
      String countryCode         = exchange.getIn().getHeader(ROUTING_SLIP_COUNTRY,  String.class);
      String scenarioName        = exchange.getIn().getHeader(ROUTING_SLIP_SCENARIO, String.class);
      int    instanceId          = exchange.getIn().getHeader(ROUTING_SLIP_INSTANCE, 0, Integer.class);
      String scenarioSourceQueue = exchange.getIn().getHeader(SCENARIO_SOURCE_QUEUE, String.class);
      String routeName           = exchange.getIn().getHeader(CURRENT_ROUTE_NAME,    String.class);
      String routeSource         = exchange.getIn().getHeader(ROUTE_SOURCE,          String.class);
      String routeTarget         = exchange.getIn().getHeader(ROUTE_TARGET,          String.class);
      String startTimestamp      = exchange.getIn().getHeader(ROUTE_START_TIMESTAMP, String.class);
      String payload             = exchange.getIn().getBody(String.class);

      ObjectNode auditNode = objectMapper.createObjectNode();
      auditNode.put("OriginalMessageId",  originalMessageId);
      auditNode.put("SourcePutTimestamp", sourcePutTimestamp);
      auditNode.put("EventTimestamp",     Instant.now().toString());
      auditNode.put("MessageId",          messageId);

      ObjectNode headersNode = auditNode.putObject("Headers");
      headersNode.put("auditQueue", scenarioSourceQueue);

      ObjectNode routingSlip = headersNode.putObject("RoutingSlip");
      routingSlip.put("CountryCode",  countryCode);
      routingSlip.put("ScenarioName", scenarioName);
      routingSlip.put("InstanceId",   instanceId);

      ObjectNode routeInfo = headersNode.putObject("RouteInfo");
      routeInfo.put("RouteName",      routeName);
      routeInfo.put("source",         routeSource);
      routeInfo.put("target",         routeTarget);
      routeInfo.put("startTimestamp", startTimestamp);
      routeInfo.put("endTimestamp",   Instant.now().toString());

      auditNode.put("Payload", payload);

      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(auditNode);

    } catch (Exception e) {
      log.error("[AuditJsonBuilder] Failed to build audit JSON — {}", e.getMessage(), e);
      return null;
    }
  }
}