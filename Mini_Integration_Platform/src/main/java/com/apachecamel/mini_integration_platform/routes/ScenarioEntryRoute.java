package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.ScenarioProcessor;
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
    private final AuditJsonBuilder     auditJsonBuilder;
    private final ExceptionJsonBuilder exceptionJsonBuilder;

    @Value("${app.queue.audit:COMMON.AUDIT.SERVICE.IN}")
    private String auditQueue;

    @Value("${app.queue.exception:COMMON.EXCEPTION.SERVICE.IN}")
    private String exceptionQueue;

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
                .wireTap("activemq:" + exceptionQueue)
                .onPrepare(ex -> {
                    String json = exceptionJsonBuilder.build(ex);
                    ex.getIn().setBody(json);
                })
                .end()
                .log("Route1 [" + routeId + "] exception dispatched (async)")
                .end()

                // ── Step 1: Detect format — transform XML → JSON via XSLT ──────────
                // If body starts with "<" it is XML — apply XSLT stylesheet which
                // dynamically maps any XML field to a JSON key-value pair.
                // If body is already JSON — leave untouched, stamp JSON header.
                // Both paths stamp OriginalFormat header for full traceability.
                .choice()
                .when(body().startsWith("<"))
                .to("xslt:xslt/xml-to-json.xsl")
                .setHeader(ScenarioProcessor.ORIGINAL_FORMAT,
                        constant(ScenarioProcessor.FORMAT_XML))
                .log("Route1 XML transformed to JSON via XSLT")
                .otherwise()
                .setHeader(ScenarioProcessor.ORIGINAL_FORMAT,
                        constant(ScenarioProcessor.FORMAT_JSON))
                .end()

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

                // ── Step 3: Forward to CORE.ENTRY.SERVICE.IN  ✅ ─────────────────
                .to("activemq:" + targetQueue)
                .log("Route1 [" + routeId + "] forwarded to " + targetQueue)

                // ── Step 4: EXIT audit — message leaving Route1 ───────────────────
                // eventTimestamp IS populated — message has been forwarded
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.buildExit(ex);
                    ex.getIn().setBody(json);
                })
                .end()

                .log("Route1 [" + routeId + "] EXIT audit dispatched (async)");
    }
}