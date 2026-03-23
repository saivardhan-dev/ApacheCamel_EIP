package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.ServiceConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.ScenarioProcessor;
import com.apachecamel.mini_integration_platform.processor.MessageSplitterProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apachecamel.mini_integration_platform.service.AuditJsonBuilder;
import com.apachecamel.mini_integration_platform.service.ExceptionJsonBuilder;
import com.apachecamel.mini_integration_platform.service.ScenarioCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ScenarioEntryRoute — Route-1 for every scenario in EhCache.
 *
 * Flow (happy path):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Source Queue                                                        │
 * │       ↓                                                              │
 * │  ScenarioProcessor  → stamps all headers + LegIndex=0               │
 * │       ↓                                                              │
 * │  .to(CORE.ENTRY.SERVICE.IN)   ✅ MAIN JOB DONE                      │
 * │       ↓                                                              │
 * │  wireTap(COMMON.AUDIT.SERVICE.IN)  ── async thread ─────────────►   │
 * │       onPrepare: AuditJsonBuilder.build() → setBody(auditJson)      │
 * │  THREAD RELEASED                                                     │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * Flow (exception path):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Any step throws                                                     │
 * │       ↓                                                              │
 * │  onException handler                                                 │
 * │       ↓                                                              │
 * │  wireTap(COMMON.EXCEPTION.SERVICE.IN)  ── async thread ──────────►  │
 * │       onPrepare: ExceptionJsonBuilder.build() → setBody(json)       │
 * │  THREAD RELEASED                                                     │
 * └──────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScenarioEntryRoute extends RouteBuilder {

    private final ScenarioCacheService scenarioCacheService;
    private final AuditJsonBuilder          auditJsonBuilder;
    private final MessageSplitterProcessor messageSplitterProcessor;
    private final ObjectMapper             objectMapper;
    private final ExceptionJsonBuilder exceptionJsonBuilder;

    @Value("${app.queue.audit:COMMON.AUDIT.SERVICE.IN}")
    private String auditQueue;

    @Value("${app.queue.exception:COMMON.EXCEPTION.SERVICE.IN}")
    private String exceptionQueue;

    @Value("${app.queue.dlq:COMMON.DLQ.SERVICE.IN}")
    private String dlqQueue;



    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void configure() {
        List<Scenario> scenarios = scenarioCacheService.getAllScenarios();

        if (scenarios.isEmpty()) {
            log.warn("[ScenarioEntryRoute] No scenarios found in cache — no routes created.");
            return;
        }

        for (Scenario scenario : scenarios) {
            buildRoute1(scenario);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    private void buildRoute1(Scenario scenario) {
        RouteConfig route1Config = scenario.findRoute("Route1");

        if (route1Config == null) {
            log.warn("[ScenarioEntryRoute] No Route1 for scenario '{}' — skipping.",
                    scenario.getCacheKey());
            return;
        }

        String sourceQueue = scenario.getEffectiveSourceQueue();
        String targetQueue = route1Config.getTarget();
        String routeId     = "route1-" + scenario.getCacheKey();

        ScenarioProcessor scenarioProcessor = new ScenarioProcessor(scenario, route1Config);

        log.info("[ScenarioEntryRoute] Registering Route1 '{}': {} → {}",
                routeId, sourceQueue, targetQueue);

        from("activemq:" + sourceQueue)
                .routeId(routeId)

                // ── Exception handler — async, non-blocking ───────────────────────
                .onException(Exception.class)
                .handled(true)
                // ── wireTap 1: Exception queue → ExceptionRoute ───────────────
                // ExceptionRoute consumes, reads ExceptionCode from EhCache,
                // persists to MongoDB, triggers AI analysis + email.
                .wireTap("activemq:" + exceptionQueue)
                .onPrepare(ex -> {
                    String json = exceptionJsonBuilder.build(ex);
                    ex.getIn().setBody(json);
                })
                .end()
                // ── wireTap 2: DLQ → raw payload only, no consumer ───────────
                // Original message payload stored as-is for inspection.
                // No headers, no metadata — just the raw body.
                .wireTap("activemq:" + dlqQueue)
                .end()
                .log("Route1 [" + routeId + "] exception + DLQ dispatched (async)")
                .end()

                // ── Step 0: Splitter — detect envelope, split if needed ─────────────
                // Detects { "AppID": "...", "messages": [...] } envelope format.
                // Single message → passthrough unchanged.
                // Envelope → body replaced with first message,
                //            remaining messages stored as pendingMessages.
                .process(messageSplitterProcessor)

                // ── Step 1: Service — read inline from Route1 config ────────────────
                // route1Config.getService() → ServiceConfig or null
                // Scenario1 Route1: service = null → passthrough
                // Scenario2 Route1: service.type = XSLT → transform XML to JSON
                .process(exchange -> {
                    ServiceConfig service = route1Config.getService();

                    if (service == null || !service.isXslt()) {
                        // No XSLT service on this route — passthrough
                        return;
                    }

                    String body = exchange.getIn().getBody(String.class);
                    if (body != null && body.trim().startsWith("<")) {
                        // XML detected — apply XSLT stylesheet from service config
                        String xsltUri = "xslt:" + service.getXsltPath();
                        exchange.getContext()
                                .createProducerTemplate()
                                .send(xsltUri, exchange);
                        exchange.getIn().setHeader(ScenarioProcessor.ORIGINAL_FORMAT,
                                ScenarioProcessor.FORMAT_XML);
                        log.info("[ScenarioEntryRoute] XML → JSON via XSLT — service='{}' path='{}'",
                                service.getServiceId(), service.getXsltPath());
                    } else {
                        // JSON detected — passthrough, stamp format header only
                        exchange.getIn().setHeader(ScenarioProcessor.ORIGINAL_FORMAT,
                                ScenarioProcessor.FORMAT_JSON);
                    }
                })

                // ── Step 2: Stamp all routing headers ─────────────────────────────
                .process(scenarioProcessor)
                .log("Route1 [" + routeId + "] received — msgId=${header.OriginalMessageId} format=${header.OriginalFormat}")

                // ── Step 2: ENTRY audit — message entering Route1 ─────────────────
                // eventTimestamp is EMPTY — message has not left the route yet
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.buildEntry(ex);
                    ex.getIn().setBody(json);
                })
                .end()
                .log("Route1 [" + routeId + "] ENTRY audit dispatched (async)")

                // ── Step 3: Forward to CORE.ENTRY.SERVICE.IN ─────────────────────
                // Uses InOnly (fire-and-forget) — ScenarioEntryRoute does NOT wait
                // for CoreProcessingRoute to finish. This ensures Steps 4, 5, 6
                // always execute regardless of whether the message fails downstream.
                // Each split message is independent — a failure in Cars/700 must
                // not prevent Electronics/1100 and Furniture/1500 from being re-queued.
                .to(org.apache.camel.ExchangePattern.InOnly, "activemq:" + targetQueue)
                .log("Route1 [" + routeId + "] forwarded to " + targetQueue)

                // ── Step 4: EXIT audit — message leaving Route1 ───────────────────
                // eventTimestamp IS populated — message has been forwarded
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.buildExit(ex);
                    ex.getIn().setBody(json);
                })
                .end()

                .log("Route1 [" + routeId + "] EXIT audit dispatched (async)")

                // ── Step 6: Re-queue pending split messages ───────────────────────
                // If this was a split from an envelope, remaining messages are
                // sent back to the source queue individually so each flows
                // through the full route with its own OriginalMessageId,
                // audit trail, and exception handling.
                .process(exchange -> {
                    java.util.List<java.util.Map<String, Object>> pending =
                            exchange.getProperty(
                                    MessageSplitterProcessor.PROP_PENDING_MESSAGES,
                                    java.util.List.class);

                    if (pending == null || pending.isEmpty()) {
                        return;
                    }

                    // Read appId using configured appIdField from SplitterConfig
                    String appId = exchange.getIn().getHeader(
                            MessageSplitterProcessor.HEADER_APP_ID, String.class);
                    int    batchSize = exchange.getProperty(
                            MessageSplitterProcessor.PROP_BATCH_SIZE, 0, Integer.class);

                    log.info("[ScenarioEntryRoute] Re-queuing {} remaining split messages " +
                            "to '{}' AppID='{}'", pending.size(), sourceQueue, appId);

                    org.apache.camel.ProducerTemplate producer =
                            exchange.getContext().createProducerTemplate();

                    int index = 2;
                    for (java.util.Map<String, Object> msg : pending) {
                        String msgJson = objectMapper.writeValueAsString(msg);

                        // Each pending message sent as a fresh independent exchange
                        producer.sendBodyAndHeader(
                                "activemq:" + sourceQueue,
                                msgJson,
                                MessageSplitterProcessor.HEADER_APP_ID,
                                appId
                        );

                        log.info("[ScenarioEntryRoute] Re-queued {}/{} — body='{}'",
                                index++, batchSize, msgJson);
                    }

                    // Clear pending list — processed
                    exchange.setProperty(MessageSplitterProcessor.PROP_PENDING_MESSAGES, null);
                });
    }
}