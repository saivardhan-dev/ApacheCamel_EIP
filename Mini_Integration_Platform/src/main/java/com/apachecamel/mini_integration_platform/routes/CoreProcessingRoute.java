package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.AuditProcessor;
import com.apachecamel.mini_integration_platform.processor.ExceptionProcessor;
import com.apachecamel.mini_integration_platform.processor.ScenarioProcessor;
import com.apachecamel.mini_integration_platform.service.ScenarioCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * CoreProcessingRoute  (Route-2)
 *
 * A SINGLE route that listens on CORE.ENTRY.SERVICE.IN.
 * It uses the RoutingSlip headers placed by Route-1 (ScenarioEntryRoute)
 * to dynamically look up the correct Scenario from EhCache and determine
 * the Route-2 target queue for that scenario.
 *
 * Flow:
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  CORE.ENTRY.SERVICE.IN                                                   │
 * │       ↓                                                                  │
 * │  Read RoutingSlip headers → look up Scenario from EhCache               │
 * │       ↓                                                                  │
 * │  Resolve Route2 definition from Scenario                                 │
 * │       ↓                                                                  │
 * │  Update RouteInfo headers with Route2 details                            │
 * │       ↓                                                                  │
 * │  (Business logic / transformation can be added here)                     │
 * │       ↓                                                                  │
 * │  .toD("activemq:${exchangeProperty.route2Target}")   ← dynamic routing  │
 * │       ↓                                                                  │
 * │  AuditProcessor → builds Audit.json, sends to COMMON.AUDIT.SERVICE.IN   │
 * │                                                                          │
 * │  On ANY Exception:                                                       │
 * │  ExceptionProcessor → builds Exception.json, sends to                   │
 * │                        COMMON.EXCEPTION.SERVICE.IN                      │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreProcessingRoute extends RouteBuilder {

    private final ScenarioCacheService scenarioCacheService;
    private final AuditProcessor       auditProcessor;
    private final ExceptionProcessor   exceptionProcessor;

    @Value("${app.queue.core-entry}")
    private String coreEntryQueue;   // CORE.ENTRY.SERVICE.IN

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void configure() {
        log.info("[CoreProcessingRoute] Creating Route2 on queue='{}'", coreEntryQueue);

        from("activemq:" + coreEntryQueue)
                .routeId("route2-core-processing")

                // ── Exception handler ─────────────────────────────────────────────
                .onException(Exception.class)
                .handled(true)
                .process(exceptionProcessor)
                .end()

                // ── Step 1: Read RoutingSlip headers set by Route-1 ───────────────
                // Resolve scenario from EhCache and prepare Route-2 routing details
                .process(exchange -> {
                    String countryCode  = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_COUNTRY,  String.class);
                    String scenarioName = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_SCENARIO, String.class);
                    int    instanceId   = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_INSTANCE, 0, Integer.class);

                    // Guard — RoutingSlip must exist
                    if (countryCode == null || scenarioName == null) {
                        throw new IllegalStateException(
                                "Missing RoutingSlip headers on CORE.ENTRY.SERVICE.IN message. " +
                                        "CountryCode=" + countryCode + ", ScenarioName=" + scenarioName
                        );
                    }

                    // ── Look up Scenario from EhCache ──────────────────────────────
                    Scenario scenario = scenarioCacheService.getScenario(countryCode, scenarioName, instanceId);
                    if (scenario == null) {
                        throw new IllegalStateException(
                                "Scenario not found in cache: " +
                                        ScenarioCacheService.buildKey(countryCode, scenarioName, instanceId)
                        );
                    }

                    // ── Resolve Route-2 definition ─────────────────────────────────
                    RouteConfig route2Config = scenario.findRoute("Route2");
                    if (route2Config == null) {
                        throw new IllegalStateException(
                                "No Route2 defined for scenario: " + scenario.getCacheKey()
                        );
                    }

                    log.info("[CoreProcessingRoute] Routing scenario='{}' to Route2 target='{}'",
                            scenario.getCacheKey(), route2Config.getTarget());

                    // ── Update RouteInfo headers for Route-2 ───────────────────────
                    exchange.getIn().setHeader(ScenarioProcessor.CURRENT_ROUTE_NAME,    route2Config.getRouteName());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_SOURCE,          route2Config.getSource());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET,          route2Config.getTarget());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_START_TIMESTAMP, Instant.now().toString());

                    // Store Route-2 target in exchange property for .toD() below
                    exchange.setProperty("route2Target", route2Config.getTarget());
                })

                .log("Route2 [route2-core-processing] processing for scenario=${header.RoutingSlip_ScenarioName}")

                // ── Step 2: Business processing placeholder ────────────────────────
                // Any transformation, enrichment or validation for Route-2 goes here
                // e.g. .process(myTransformationProcessor)

                // ── Step 3: Dynamically route to the scenario's exit queue ─────────
                // e.g. GATEWAY.EXIT.WW.SCENARIO1.1.OUT or GATEWAY.EXIT.WW.SCENARIO2.1.OUT
                .toD("activemq:${exchangeProperty.route2Target}")
                .log("Route2 forwarded to ${exchangeProperty.route2Target}")

                // ── Step 4: Send Audit to COMMON.AUDIT.SERVICE.IN ─────────────────
                .process(auditProcessor)
                .log("Route2 [route2-core-processing] audit sent");
    }
}









