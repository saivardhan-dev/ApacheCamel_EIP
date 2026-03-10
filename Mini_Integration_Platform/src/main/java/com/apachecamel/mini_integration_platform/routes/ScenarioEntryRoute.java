package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.AuditProcessor;
import com.apachecamel.mini_integration_platform.processor.ExceptionProcessor;
import com.apachecamel.mini_integration_platform.processor.ScenarioProcessor;
import com.apachecamel.mini_integration_platform.service.ScenarioCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ScenarioEntryRoute  (Route-1)
 *
 * Dynamically creates ONE Camel route for EVERY scenario loaded from EhCache.
 * So if Scenarios.json has 2 scenarios, 2 independent Route-1 routes are created:
 *
 *   route1-WW_Scenario1_1   listens on  GATEWAY.ENTRY.WW.SCENARIO1.1.IN
 *   route1-WW_Scenario2_1   listens on  GATEWAY.ENTRY.WW.SCENARIO2.1.IN
 *
 * Per-route flow:
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  Source Queue (per scenario)                                             │
 * │       ↓                                                                  │
 * │  ScenarioProcessor  → stamps OriginalMessageId, SourcePutTimestamp,      │
 * │                        RoutingSlip headers, RouteInfo headers            │
 * │       ↓                                                                  │
 * │  .to(CORE.ENTRY.SERVICE.IN)   → hands off to Route-2                    │
 * │       ↓                                                                  │
 * │  AuditProcessor     → builds Audit.json, sends to COMMON.AUDIT.SERVICE.IN│
 * │                                                                          │
 * │  On ANY Exception:                                                       │
 * │  ExceptionProcessor → builds Exception.json, sends to                   │
 * │                        COMMON.EXCEPTION.SERVICE.IN                      │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScenarioEntryRoute extends RouteBuilder {

    private final ScenarioCacheService scenarioCacheService;
    private final AuditProcessor       auditProcessor;
    private final ExceptionProcessor   exceptionProcessor;

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void configure() {
        List<Scenario> scenarios = scenarioCacheService.getAllScenarios();

        if (scenarios.isEmpty()) {
            log.warn("[ScenarioEntryRoute] No scenarios found in cache — no Route-1 routes created.");
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
            log.warn("[ScenarioEntryRoute] Scenario '{}' has no Route1 definition — skipping.",
                    scenario.getCacheKey());
            return;
        }

        String sourceQueue = scenario.getEffectiveSourceQueue();
        String targetQueue = route1Config.getTarget();              // CORE.ENTRY.SERVICE.IN
        String routeId     = "route1-" + scenario.getCacheKey();   // e.g. "route1-WW_Scenario1_1"

        // Create one ScenarioProcessor instance per scenario (holds scenario + route1Config)
        ScenarioProcessor scenarioProcessor = new ScenarioProcessor(scenario, route1Config);

        log.info("[ScenarioEntryRoute] Creating Route1: id='{}' from='{}' to='{}'",
                routeId, sourceQueue, targetQueue);

        from("activemq:" + sourceQueue)
                .routeId(routeId)

                // ── Exception handler — catches any error in this route ────────────
                .onException(Exception.class)
                .handled(true)
                .process(exceptionProcessor)
                .end()

                // ── Step 1: Stamp all headers via ScenarioProcessor ───────────────
                .process(scenarioProcessor)
                .log("Route1 [" + routeId + "] received message — messageId=${header.OriginalMessageId}")

                // ── Step 2: Forward message to CORE.ENTRY.SERVICE.IN ──────────────
                .to("activemq:" + targetQueue)
                .log("Route1 [" + routeId + "] forwarded to " + targetQueue)

                // ── Step 3: Send Audit to COMMON.AUDIT.SERVICE.IN ─────────────────
                .process(auditProcessor)
                .log("Route1 [" + routeId + "] audit sent");
    }
}








