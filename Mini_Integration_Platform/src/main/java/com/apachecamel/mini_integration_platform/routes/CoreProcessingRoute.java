package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.MessageValidatorProcessor;
import com.apachecamel.mini_integration_platform.processor.ScenarioProcessor;
import com.apachecamel.mini_integration_platform.service.AuditJsonBuilder;
import com.apachecamel.mini_integration_platform.service.ExceptionJsonBuilder;
import com.apachecamel.mini_integration_platform.service.ScenarioCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * CoreProcessingRoute — handles ALL route legs after Route-1 for every scenario.
 *
 * Flow (happy path — per leg):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  CORE.ENTRY.SERVICE.IN                                               │
 * │       ↓                                                              │
 * │  Step 1: EhCache lookup → increment LegIndex → update RouteInfo     │
 * │       ↓                                                              │
 * │  Step 2: MessageValidatorProcessor                                   │
 * │       ↓                                                              │
 * │  Step 3: .toD(exit queue)   ✅ MAIN JOB DONE                        │
 * │       ↓                                                              │
 * │  Step 4: wireTap(COMMON.AUDIT.SERVICE.IN)  ── async thread ──────►  │
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
 * │       reads EXCEPTION_CAUGHT + current leg headers                  │
 * │  THREAD RELEASED                                                     │
 * └──────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreProcessingRoute extends RouteBuilder {

    private final ScenarioCacheService      scenarioCacheService;
    private final MessageValidatorProcessor messageValidatorProcessor;
    private final AuditJsonBuilder          auditJsonBuilder;
    private final ExceptionJsonBuilder      exceptionJsonBuilder;

    @Value("${app.queue.core-entry:CORE.ENTRY.SERVICE.IN}")
    private String coreEntryQueue;

    @Value("${app.queue.audit:COMMON.AUDIT.SERVICE.IN}")
    private String auditQueue;

    @Value("${app.queue.exception:COMMON.EXCEPTION.SERVICE.IN}")
    private String exceptionQueue;

    private static final String PROP_CURRENT_LEG_INDEX = "CurrentRouteLegIndex";

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void configure() {
        log.info("[CoreProcessingRoute] Registering core handler on '{}'", coreEntryQueue);

        from("activemq:" + coreEntryQueue)
                .routeId("route-core-processing")

                // ── Exception handler — async, non-blocking ───────────────────────
                // ExceptionJsonBuilder reads EXCEPTION_CAUGHT from the exchange and
                // the current leg's RouteInfo headers — so it always knows exactly
                // which leg failed (Route2, Route3, etc.) and builds the correct JSON.
                .onException(Exception.class)
                .handled(true)
                .wireTap("activemq:" + exceptionQueue)
                .onPrepare(ex -> {
                    String json = exceptionJsonBuilder.build(ex);
                    ex.getIn().setBody(json);
                })
                .end()
                .log("Exception dispatched for leg=${header.CurrentRouteName} (async)")
                .end()

                // ── Step 1: EhCache lookup → determine and update next route leg ──
                .process(exchange -> {
                    String countryCode  = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_COUNTRY,  String.class);
                    String scenarioName = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_SCENARIO, String.class);
                    int    instanceId   = exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_INSTANCE, 0, Integer.class);

                    if (countryCode == null || scenarioName == null) {
                        throw new IllegalStateException(
                                "Missing RoutingSlip headers — CountryCode=" + countryCode +
                                        ", ScenarioName=" + scenarioName);
                    }

                    Scenario scenario = scenarioCacheService.getScenario(countryCode, scenarioName, instanceId);
                    if (scenario == null) {
                        throw new IllegalStateException(
                                "Scenario not found: " +
                                        ScenarioCacheService.buildKey(countryCode, scenarioName, instanceId));
                    }

                    List<RouteConfig> allRoutes = scenario.getRoutes();

                    // Route-1 stamped LegIndex=0. Increment to get next leg.
                    int lastLegIndex = exchange.getProperty(PROP_CURRENT_LEG_INDEX, 0, Integer.class);
                    int nextLegIndex = lastLegIndex + 1;

                    if (nextLegIndex >= allRoutes.size()) {
                        throw new IllegalStateException(
                                "No more route legs for scenario '" + scenario.getCacheKey() +
                                        "' — lastLegIndex=" + lastLegIndex +
                                        ", totalRoutes=" + allRoutes.size());
                    }

                    RouteConfig nextLeg = allRoutes.get(nextLegIndex);

                    log.info("[CoreProcessingRoute] Executing leg '{}' ({}/{}) for '{}' → '{}'",
                            nextLeg.getRouteName(), nextLegIndex + 1, allRoutes.size(),
                            scenario.getCacheKey(), nextLeg.getTarget());

                    // Update RouteInfo headers for this leg — AuditJsonBuilder and
                    // ExceptionJsonBuilder both read these to build their JSON
                    exchange.getIn().setHeader(ScenarioProcessor.CURRENT_ROUTE_NAME,    nextLeg.getRouteName());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_SOURCE,          nextLeg.getSource());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET,          nextLeg.getTarget());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_START_TIMESTAMP, Instant.now().toString());
                    exchange.setProperty(PROP_CURRENT_LEG_INDEX, nextLegIndex);
                    exchange.setProperty("routeTarget",           nextLeg.getTarget());
                })

                .log("Processing leg=${header.CurrentRouteName} scenario=${header.RoutingSlip_ScenarioName}")

                // ── Step 2: Validate message ──────────────────────────────────────
                // If this throws → onException fires → ExceptionJsonBuilder builds
                // JSON with the current leg's headers → sent async to exception queue
                .process(messageValidatorProcessor)

                // ── Step 3: Forward to this leg's target queue  ✅ ─────────────────
                .toD("activemq:${exchangeProperty.routeTarget}")
                .log("Delivered to ${exchangeProperty.routeTarget} — leg=${header.CurrentRouteName}")

                // ── Step 4: Audit this leg — async, non-blocking ──────────────────
                // AuditJsonBuilder reads the current leg's RouteInfo headers — so
                // each leg gets its own Audit record with the correct RouteName,
                // RouteSource, RouteTarget and timestamps.
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.build(ex);
                    ex.getIn().setBody(json);
                })
                .end()

                .log("Audit dispatched for leg=${header.CurrentRouteName} (async)");
    }
}