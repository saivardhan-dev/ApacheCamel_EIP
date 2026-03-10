package com.apachecamel.mini_integration_platform.processor;

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

import java.time.Instant;

import static com.apachecamel.mini_integration_platform.processor.ScenarioProcessor.*;

/**
 * AuditProcessor
 *
 * Builds an Audit message matching Audit.json and sends it to
 * COMMON.AUDIT.SERVICE.IN.
 *
 * FIX for circular dependency:
 *   ProducerTemplate is NO LONGER injected via constructor/field at startup.
 *   Instead, CamelContext is injected (which is safe — it is the context itself,
 *   not a component that depends on it), and ProducerTemplate is created lazily
 *   on first use via camelContext.createProducerTemplate().
 *   This breaks the camelContext → AuditProcessor → ProducerTemplate → camelContext cycle.
 *
 * FIX for @Value placeholder not resolved:
 *   Queue name is injected via @Value with a safe default fallback so the bean
 *   can be constructed even if properties load order varies.
 */
@Slf4j
@Component
public class AuditProcessor implements Processor {

    // CamelContext injection is safe — it does not trigger circular creation
    @Autowired
    private CamelContext camelContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.queue.audit:COMMON.AUDIT.SERVICE.IN}")
    private String auditQueue;

    // Lazily initialised — only created after CamelContext is fully started
    private ProducerTemplate producerTemplate;

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void process(Exchange exchange) {
        try {
            buildAndSendAudit(exchange, Instant.now().toString());
        } catch (Exception e) {
            // Audit failure must NEVER disrupt the main message flow
            log.error("[AuditProcessor] Failed to send audit - {}", e.getMessage(), e);
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

    private void buildAndSendAudit(Exchange exchange, String endTimestamp) throws Exception {

        String originalMessageId   = exchange.getIn().getHeader(ORIGINAL_MESSAGE_ID,   String.class);
        String sourcePutTimestamp  = exchange.getIn().getHeader(SOURCE_PUT_TIMESTAMP,   String.class);
        String messageId           = exchange.getIn().getMessageId();
        String countryCode         = exchange.getIn().getHeader(ROUTING_SLIP_COUNTRY,   String.class);
        String scenarioName        = exchange.getIn().getHeader(ROUTING_SLIP_SCENARIO,  String.class);
        int    instanceId          = exchange.getIn().getHeader(ROUTING_SLIP_INSTANCE,  0, Integer.class);
        String scenarioSourceQueue = exchange.getIn().getHeader(SCENARIO_SOURCE_QUEUE,  String.class);
        String routeName           = exchange.getIn().getHeader(CURRENT_ROUTE_NAME,     String.class);
        String routeSource         = exchange.getIn().getHeader(ROUTE_SOURCE,           String.class);
        String routeTarget         = exchange.getIn().getHeader(ROUTE_TARGET,           String.class);
        String startTimestamp      = exchange.getIn().getHeader(ROUTE_START_TIMESTAMP,  String.class);
        String payload             = exchange.getIn().getBody(String.class);

        // ── Build JSON matching Audit.json structure ───────────────────────────
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
        routeInfo.put("endTimestamp",   endTimestamp);

        auditNode.put("Payload", payload);

        String auditJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(auditNode);

        // ── Send to COMMON.AUDIT.SERVICE.IN ────────────────────────────────────
        getProducerTemplate().sendBody("activemq:" + auditQueue, auditJson);

        log.info("[AuditProcessor] Audit sent - messageId='{}' route='{}' endTime='{}'",
                messageId, routeName, endTimestamp);
    }
}