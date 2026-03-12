package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.MessageValidatorProcessor;
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

import java.time.Instant;
import java.util.List;

/**
 * CoreProcessingRoute — handles ALL route legs after Route-1 for every scenario.
 *
 * Both audit AND exception are sent via wireTap — fully async, non-blocking.
 *
 * Flow (happy path — per leg):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  CORE.ENTRY.SERVICE.IN                                               │
 * │       ↓                                                              │
 * │  Step 1: EhCache lookup → update RouteInfo headers for this leg     │
 * │       ↓                                                              │
 * │  Step 2: MessageValidatorProcessor                                   │
 * │       ↓                                                              │
 * │  Step 3: wireTap("log:wiretap") → MongoDB POST_VALIDATION (async)   │
 * │       ↓                                                              │
 * │  Step 4: .toD(exit queue)  ✅ MAIN JOB DONE                         │
 * │       ↓                                                              │
 * │  Step 5: wireTap("log:wiretap") → MongoDB EXIT (async)              │
 * │       ↓                                                              │
 * │  Step 6: wireTap(auditQueue) → COMMON.AUDIT.SERVICE.IN (async)     │
 * │  THREAD RELEASED                                                     │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * Flow (exception path):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Any step throws                                                     │
 * │       ↓                                                              │
 * │  onException handler                                                 │
 * │       ↓                                                              │
 * │  wireTap(exceptionQueue) → COMMON.EXCEPTION.SERVICE.IN (async)       │
 * │       onPrepare: ExceptionJsonBuilder.build()                        │
 * │  THREAD RELEASED — exception does NOT block the route thread         │
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
    private final WireTapPersistenceService wireTapPersistenceService;

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
                // ExceptionJsonBuilder reads the EXCEPTION_CAUGHT property from the
                // exchange automatically — it knows exactly which leg failed and
                // which route headers (RouteName, source, target) were active.
                // wireTap fires on a separate thread — route thread released immediately.
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

                // ── Step 1: EhCache lookup → determine next route leg ─────────────
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

                    // Increment leg index — Route1 set it to 0, so first arrival = index 1 (Route2)
                    int lastLegIndex = exchange.getProperty(PROP_CURRENT_LEG_INDEX, 0, Integer.class);
                    int nextLegIndex = lastLegIndex + 1;

                    if (nextLegIndex >= allRoutes.size()) {
                        throw new IllegalStateException(
                                "No more route legs for scenario '" + scenario.getCacheKey() +
                                        "' — lastLegIndex=" + lastLegIndex + ", totalRoutes=" + allRoutes.size());
                    }

                    RouteConfig nextLeg = allRoutes.get(nextLegIndex);

                    log.info("[CoreProcessingRoute] Executing leg '{}' ({}/{}) for scenario '{}' → '{}'",
                            nextLeg.getRouteName(), nextLegIndex + 1, allRoutes.size(),
                            scenario.getCacheKey(), nextLeg.getTarget());

                    // Update RouteInfo headers for this leg
                    // These are what ExceptionJsonBuilder, AuditJsonBuilder, and
                    // WireTapPersistenceService all read to identify the current leg
                    exchange.getIn().setHeader(ScenarioProcessor.CURRENT_ROUTE_NAME,    nextLeg.getRouteName());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_SOURCE,          nextLeg.getSource());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET,          nextLeg.getTarget());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_START_TIMESTAMP, Instant.now().toString());
                    exchange.setProperty(PROP_CURRENT_LEG_INDEX, nextLegIndex);
                    exchange.setProperty("routeTarget", nextLeg.getTarget());
                })

                .log("Processing leg=${header.CurrentRouteName} for scenario=${header.RoutingSlip_ScenarioName}")

                // ── Step 2: Validate message ──────────────────────────────────────
                // If this throws, onException fires → ExceptionJsonBuilder builds
                // JSON with the current leg's headers → wireTap sends to exception queue
                .process(messageValidatorProcessor)

                // ── Step 3: Wire Tap — post-validation snapshot (async) ───────────
                .wireTap("log:wiretap")
                .onPrepare(ex -> wireTapPersistenceService.save(ex,
                        ex.getIn().getHeader(ScenarioProcessor.CURRENT_ROUTE_NAME, String.class)
                                + "_POST_VALIDATION"))
                .end()

                // ── Step 4: Forward to this leg's target queue  ✅ ─────────────────
                .toD("activemq:${exchangeProperty.routeTarget}")
                .log("Delivered to ${exchangeProperty.routeTarget} — leg=${header.CurrentRouteName}")

                // ── Step 5: Wire Tap — exit snapshot (async) ──────────────────────
                .wireTap("log:wiretap")
                .onPrepare(ex -> wireTapPersistenceService.save(ex,
                        ex.getIn().getHeader(ScenarioProcessor.CURRENT_ROUTE_NAME, String.class)
                                + "_EXIT"))
                .end()

                // ── Step 6: Audit this leg — async, non-blocking ──────────────────
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.build(ex);
                    ex.getIn().setBody(json);
                })
                .end()

                .log("Audit dispatched for leg=${header.CurrentRouteName} (async)");
    }
}