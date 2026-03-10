package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.AuditProcessor;
import com.apachecamel.mini_integration_platform.processor.ExceptionProcessor;
import com.apachecamel.mini_integration_platform.processor.MessageValidatorProcessor;
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
 * Listens on CORE.ENTRY.SERVICE.IN.
 * Reads RoutingSlip headers from Route-1, looks up the Scenario from EhCache,
 * validates the message via MessageValidatorProcessor, then dynamically routes
 * to the correct exit queue.
 *
 * Flow:
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  CORE.ENTRY.SERVICE.IN                                                   │
 * │       ↓                                                                  │
 * │  Step 1: Read RoutingSlip → EhCache lookup → update RouteInfo headers   │
 * │       ↓                                                                  │
 * │  Step 2: MessageValidatorProcessor → throws if invalid/ERROR body       │
 * │       ↓                                                                  │
 * │  Step 3: .toD() → dynamic exit queue                                    │
 * │       ↓                                                                  │
 * │  Step 4: AuditProcessor → COMMON.AUDIT.SERVICE.IN                       │
 * │                                                                          │
 * │  On ANY Exception:                                                       │
 * │  ExceptionProcessor → COMMON.EXCEPTION.SERVICE.IN                       │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreProcessingRoute extends RouteBuilder {

    private final ScenarioCacheService       scenarioCacheService;
    private final AuditProcessor             auditProcessor;
    private final ExceptionProcessor         exceptionProcessor;
    private final MessageValidatorProcessor  messageValidatorProcessor;

    @Value("${app.queue.core-entry:CORE.ENTRY.SERVICE.IN}")
    private String coreEntryQueue;

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void configure() {
        log.info("[CoreProcessingRoute] Creating Route2 on queue='{}'", coreEntryQueue);

        from("activemq:" + coreEntryQueue)
                .routeId("route2-core-processing")

                // ── Exception handler — catches anything thrown below ──────────────
                .onException(Exception.class)
                .handled(true)
                .process(exceptionProcessor)   // → builds Exception.json → COMMON.EXCEPTION.SERVICE.IN
                .end()

                // ── Step 1: Read RoutingSlip headers → EhCache lookup ─────────────
                .process(exchange -> {
                    String countryCode  = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_COUNTRY,  String.class);
                    String scenarioName = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_SCENARIO, String.class);
                    int    instanceId   = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_INSTANCE, 0, Integer.class);

                    if (countryCode == null || scenarioName == null) {
                        throw new IllegalStateException(
                                "Missing RoutingSlip headers. CountryCode=" + countryCode +
                                        ", ScenarioName=" + scenarioName
                        );
                    }

                    Scenario scenario = scenarioCacheService.getScenario(countryCode, scenarioName, instanceId);
                    if (scenario == null) {
                        throw new IllegalStateException(
                                "Scenario not found in cache: " +
                                        ScenarioCacheService.buildKey(countryCode, scenarioName, instanceId)
                        );
                    }

                    RouteConfig route2Config = scenario.findRoute("Route2");
                    if (route2Config == null) {
                        throw new IllegalStateException(
                                "No Route2 defined for scenario: " + scenario.getCacheKey()
                        );
                    }

                    log.info("[CoreProcessingRoute] Routing scenario='{}' → target='{}'",
                            scenario.getCacheKey(), route2Config.getTarget());

                    // Update RouteInfo headers for Route-2 leg
                    exchange.getIn().setHeader(ScenarioProcessor.CURRENT_ROUTE_NAME,    route2Config.getRouteName());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_SOURCE,          route2Config.getSource());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET,          route2Config.getTarget());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_START_TIMESTAMP, Instant.now().toString());

                    exchange.setProperty("route2Target", route2Config.getTarget());
                })

                .log("Route2 processing scenario=${header.RoutingSlip_ScenarioName} body=${body}")

                // ── Step 2: Validate message — throws on bad/ERROR payload ─────────
                .process(messageValidatorProcessor)

                // ── Step 3: Dynamically route to the scenario's exit queue ─────────
                .toD("activemq:${exchangeProperty.route2Target}")
                .log("Route2 forwarded to ${exchangeProperty.route2Target}")

                // ── Step 4: Send Audit to COMMON.AUDIT.SERVICE.IN ─────────────────
                .process(auditProcessor)
                .log("Route2 audit sent");
    }
}