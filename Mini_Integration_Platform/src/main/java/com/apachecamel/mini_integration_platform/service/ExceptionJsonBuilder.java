package com.apachecamel.mini_integration_platform.service;

import com.apachecamel.mini_integration_platform.service.ExceptionCodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import static com.apachecamel.mini_integration_platform.processor.ScenarioProcessor.*;

/**
 * ExceptionJsonBuilder
 *
 * Single responsibility: build the Exception JSON string from an exchange
 * and the caught throwable.
 *
 * Mirrors AuditJsonBuilder — no I/O, no sending, pure JSON construction.
 *
 * Called directly inside .wireTap().onPrepare() in the onException block
 * of both routes — no separate ExceptionProcessor step needed for sending.
 *
 * Usage in a route:
 *
 *   .onException(Exception.class)
 *       .handled(true)
 *       .wireTap("activemq:" + exceptionQueue)
 *           .onPrepare(ex -> {
 *               String json = exceptionJsonBuilder.build(ex);
 *               ex.getIn().setBody(json);
 *           })
 *       .end()
 *   .end()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExceptionJsonBuilder {

  private final ObjectMapper       objectMapper;
  private final ExceptionCodeService exceptionCodeService;

  // Header key where the built Exception JSON is stored on the exchange
  public static final String EXCEPTION_PAYLOAD_HEADER = "ExceptionPayload";

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Builds the Exception JSON string from the exchange headers, body,
   * and the caught throwable.
   *
   * Reads EXCEPTION_CAUGHT from the exchange automatically —
   * no need to pass the throwable separately.
   *
   * @param exchange  the exchange at the point of exception
   * @return          Exception JSON string matching Exception.json structure
   */
  public String build(Exchange exchange) {
    try {
      // ── Read the caught exception from exchange ────────────────────────
      Throwable thrown = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
      if (thrown == null) {
        thrown = new RuntimeException("Unknown error — no exception found in exchange");
      }

      // ── Read all routing headers ───────────────────────────────────────
      String originalMessageId  = exchange.getIn().getHeader(ORIGINAL_MESSAGE_ID,  String.class);
      String sourcePutTimestamp = exchange.getIn().getHeader(SOURCE_PUT_TIMESTAMP,  String.class);
      String messageId          = exchange.getIn().getMessageId();
      String countryCode        = exchange.getIn().getHeader(ROUTING_SLIP_COUNTRY,  String.class);
      String scenarioName       = exchange.getIn().getHeader(ROUTING_SLIP_SCENARIO, String.class);
      int    instanceId         = exchange.getIn().getHeader(ROUTING_SLIP_INSTANCE, 0, Integer.class);
      String routeName          = exchange.getIn().getHeader(CURRENT_ROUTE_NAME,    String.class);
      String routeSource        = exchange.getIn().getHeader(ROUTE_SOURCE,          String.class);
      String routeTarget        = exchange.getIn().getHeader(ROUTE_TARGET,          String.class);
      String startTimestamp     = exchange.getIn().getHeader(ROUTE_START_TIMESTAMP, String.class);
      String payload            = exchange.getIn().getBody(String.class);

      // ── Resolve exception code from EhCache ───────────────────────────
      String exceptionCode = exceptionCodeService.resolveCode(thrown);
      String stacktrace    = toStackTraceString(thrown);

      // ── Build JSON matching Exception.json structure ───────────────────
      ObjectNode exNode = objectMapper.createObjectNode();
      exNode.put("OriginalMessageId",  originalMessageId);
      exNode.put("SourcePutTimestamp", sourcePutTimestamp);
      exNode.put("EventTimestamp",     Instant.now().toString());
      exNode.put("MessageId",          messageId);

      ObjectNode headersNode = exNode.putObject("Headers");

      ObjectNode routingSlip = headersNode.putObject("RoutingSlip");
      routingSlip.put("CountryCode",  countryCode);
      routingSlip.put("ScenarioName", scenarioName);
      routingSlip.put("InstanceId",   instanceId);

      ObjectNode routeInfo = headersNode.putObject("RouteInfo");
      routeInfo.put("RouteName",      routeName);
      routeInfo.put("source",         routeSource);
      routeInfo.put("target",         routeTarget);
      routeInfo.put("startTimestamp", startTimestamp);
      routeInfo.put("endTimestamp",   ""); // always empty — failed before completion

      exNode.put("Payload",             payload);
      exNode.put("ExceptionCode",       exceptionCode);
      exNode.put("ExceptionStacktrace", stacktrace);

      log.error("[ExceptionJsonBuilder] Built exception JSON — " +
                      "msgId='{}' route='{}' code='{}' cause='{}'",
              messageId, routeName, exceptionCode, thrown.getMessage());

      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(exNode);

    } catch (Exception e) {
      log.error("[ExceptionJsonBuilder] Failed to build exception JSON — {}", e.getMessage(), e);
      return null;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────

  private String toStackTraceString(Throwable thrown) {
    StringWriter sw = new StringWriter();
    thrown.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }
}