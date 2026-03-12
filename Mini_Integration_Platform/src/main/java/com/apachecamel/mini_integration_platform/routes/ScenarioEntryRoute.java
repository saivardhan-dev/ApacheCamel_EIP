package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.ScenarioProcessor;
import com.apachecamel.mini_integration_platform.service.AuditJsonBuilder;
import com.apachecamel.mini_integration_platform.service.ExceptionJsonBuilder;
import com.apachecamel.mini_integration_platform.service.ScenarioCacheService;
import com.apachecamel.mini_integration_platform.service.persistence.WireTapPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ScenarioEntryRoute — Route-1 for every scenario in EhCache.
 *
 * Both audit AND exception are sent via wireTap — fully async, non-blocking.
 *
 * Flow (happy path):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Source Queue                                                        │
 * │       ↓                                                              │
 * │  ScenarioProcessor  → stamps all headers                            │
 * │       ↓                                                              │
 * │  wireTap("log:wiretap")  → MongoDB ROUTE1_ENTRY (async)             │
 * │       ↓                                                              │
 * │  .to(CORE.ENTRY.SERVICE.IN)  ✅ MAIN JOB DONE                       │
 * │       ↓                                                              │
 * │  wireTap(auditQueue)  → COMMON.AUDIT.SERVICE.IN (async)             │
 * │       onPrepare: AuditJsonBuilder.build()                           │
 * │  THREAD RELEASED                                                     │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * Flow (exception path):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Any step throws                                                     │
 * │       ↓                                                              │
 * │  onException handler                                                 │
 * │       ↓                                                              │
 * │  wireTap(exceptionQueue) → COMMON.EXCEPTION.SERVICE.IN (async)      │
 * │       onPrepare: ExceptionJsonBuilder.build()                       │
 * │  THREAD RELEASED — exception does NOT block the route thread        │
 * └──────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScenarioEntryRoute extends RouteBuilder {

    private final ScenarioCacheService      scenarioCacheService;
    private final AuditJsonBuilder          auditJsonBuilder;
    private final ExceptionJsonBuilder      exceptionJsonBuilder;
    private final WireTapPersistenceService wireTapPersistenceService;

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
                // When any step in Route-1 throws, the onException block fires.
                // ExceptionJsonBuilder builds the JSON from the exchange (including
                // the caught exception via EXCEPTION_CAUGHT property).
                // wireTap sends it to COMMON.EXCEPTION.SERVICE.IN on a separate thread
                // — the route thread is released immediately without waiting.
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

                // ── Step 1: Stamp all routing headers ─────────────────────────────
                .process(scenarioProcessor)
                .log("Route1 [" + routeId + "] received — msgId=${header.OriginalMessageId}")

                // ── Step 2: Wire Tap snapshot — direct MongoDB, no queue ──────────
                .wireTap("log:wiretap")
                .onPrepare(ex -> wireTapPersistenceService.save(ex, "ROUTE1_ENTRY"))
                .end()

                // ── Step 3: Forward to CORE.ENTRY.SERVICE.IN  ✅ ─────────────────
                .to("activemq:" + targetQueue)
                .log("Route1 [" + routeId + "] forwarded to " + targetQueue)

                // ── Step 4: Audit Route-1 leg — async, non-blocking ───────────────
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.build(ex);
                    ex.getIn().setBody(json);
                })
                .end()

                .log("Route1 [" + routeId + "] audit dispatched (async)");
    }
}