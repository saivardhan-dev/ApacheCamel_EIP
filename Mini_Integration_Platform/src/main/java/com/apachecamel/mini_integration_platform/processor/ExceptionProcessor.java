package com.apachecamel.mini_integration_platform.processor;

import com.apachecamel.mini_integration_platform.service.ExceptionCodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import static com.apachecamel.mini_integration_platform.processor.ScenarioProcessor.*;

/**
 * ExceptionProcessor
 *
 * Handles any exception thrown during Route-1 or Route-2 processing.
 * Builds a message matching Exception.json and sends it to
 * COMMON.EXCEPTION.SERVICE.IN.
 *
 * FIX for circular dependency:
 *   Same pattern as AuditProcessor — ProducerTemplate is created lazily
 *   from CamelContext on first use, avoiding the circular bean creation issue.
 */
@Slf4j
@Component
public class ExceptionProcessor implements Processor {

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExceptionCodeService exceptionCodeService;

    @Value("${app.queue.exception:COMMON.EXCEPTION.SERVICE.IN}")
    private String exceptionQueue;

    // Lazily initialised after CamelContext is fully started
    private ProducerTemplate producerTemplate;

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void process(Exchange exchange) {
        Throwable thrown = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        if (thrown == null) {
            thrown = new RuntimeException("Unknown error - no exception found in exchange");
        }
        try {
            buildAndSendException(exchange, thrown);
        } catch (Exception e) {
            log.error("[ExceptionProcessor] Failed to send exception message - {}", e.getMessage(), e);
        }
    }

    // ── Lazy ProducerTemplate accessor ────────────────────────────────────────

    private ProducerTemplate getProducerTemplate() {
        if (producerTemplate == null) {
            producerTemplate = camelContext.createProducerTemplate();
        }
        return producerTemplate;
    }

    // ── Core logic ─────────────────────────────────────────────────────────────

    private void buildAndSendException(Exchange exchange, Throwable thrown) throws Exception {

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

        // ── Resolve ExceptionCode via ExceptionCodeService + EhCache ──────────
        String exceptionCode = exceptionCodeService.resolveCode(thrown);
        String stacktrace    = toStackTraceString(thrown);

        // ── Build JSON matching Exception.json structure ───────────────────────
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
        routeInfo.put("endTimestamp",   "");  // empty — exception occurred before completion

        exNode.put("Payload",             payload);
        exNode.put("ExceptionCode",       exceptionCode);
        exNode.put("ExceptionStacktrace", stacktrace);

        String exceptionJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(exNode);

        // ── Send to COMMON.EXCEPTION.SERVICE.IN ───────────────────────────────
        getProducerTemplate().sendBody("activemq:" + exceptionQueue, exceptionJson);

        log.error("[ExceptionProcessor] Exception sent - messageId='{}' route='{}' code='{}' cause='{}'",
                messageId, routeName, exceptionCode, thrown.getMessage());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String toStackTraceString(Throwable thrown) {
        StringWriter sw = new StringWriter();
        thrown.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}