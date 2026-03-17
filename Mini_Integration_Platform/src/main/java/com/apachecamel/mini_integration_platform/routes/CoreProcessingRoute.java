package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.processor.MessageValidatorProcessor;
import com.apachecamel.mini_integration_platform.processor.ScenarioProcessor;
import com.apachecamel.mini_integration_platform.service.AuditJsonBuilder;
import com.apachecamel.mini_integration_platform.service.DynamicQueueResolver;
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
    private final DynamicQueueResolver      dynamicQueueResolver;

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

                // ── Step 1a: EhCache lookup → stamp RouteInfo headers only ──────────
                // DynamicQueueResolver is deliberately NOT called here.
                // Moving it to Step 3 ensures the ENTRY audit always fires first —
                // even if queue resolution fails (e.g. missing amount field).
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

                    // Stamp RouteInfo headers — AuditJsonBuilder and ExceptionJsonBuilder
                    // both read these. RouteTarget is set to the config value for now —
                    // it will be overwritten by DynamicQueueResolver in Step 3 if needed.
                    exchange.getIn().setHeader(ScenarioProcessor.CURRENT_ROUTE_NAME,    nextLeg.getRouteName());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_SOURCE,          nextLeg.getSource());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET,          nextLeg.getTarget());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_START_TIMESTAMP, Instant.now().toString());
                    exchange.setProperty(PROP_CURRENT_LEG_INDEX, nextLegIndex);

                    // Store the RouteConfig so Step 3 can access it for CBR resolution
                    exchange.setProperty("currentLegConfig", nextLeg);
                })

                .log("Processing leg=${header.CurrentRouteName} scenario=${header.RoutingSlip_ScenarioName}")

                // ── Step 2: ENTRY audit ───────────────────────────────────────────
                // Fires immediately after headers are stamped — BEFORE validation
                // and BEFORE DynamicQueueResolver. This guarantees the ENTRY audit
                // is always recorded even if subsequent steps throw exceptions.
                // The exception document will show this leg failed — and now the
                // ENTRY audit confirms the message did reach this leg.
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.buildEntry(ex);
                    ex.getIn().setBody(json);
                })
                .end()
                .log("ENTRY audit dispatched for leg=${header.CurrentRouteName} (async)")

                // ── Step 3: Resolve dynamic target queue ──────────────────────────
                // DynamicQueueResolver runs HERE — after the ENTRY audit has fired.
                // If it throws (e.g. missing amount), onException fires and the
                // exception is recorded — but the ENTRY audit is already safe.
                .process(exchange -> {
                    RouteConfig nextLeg = exchange.getProperty("currentLegConfig", RouteConfig.class);
                    if (nextLeg.isDynamicTypeAmount()) {
                        String payload  = exchange.getIn().getBody(String.class);
                        String resolved = dynamicQueueResolver.resolve(payload, nextLeg, exchange);
                        exchange.setProperty("routeTarget", resolved);
                        exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET, resolved);
                    } else {
                        exchange.setProperty("routeTarget", nextLeg.getTarget());
                    }
                })

                // ── Step 4: Validate message ──────────────────────────────────────
                .process(messageValidatorProcessor)

                // ── Step 5: Forward to this leg's target queue  ✅ ─────────────────
                .toD("activemq:${exchangeProperty.routeTarget}")
                .log("Delivered to ${exchangeProperty.routeTarget} — leg=${header.CurrentRouteName}")

                // ── Step 5: Type-only queue — wireTap, async ──────────────────────
                // Sends a copy to GATEWAY.EXIT.WW.{TYPE}.1.OUT so all messages of
                // the same type land on one queue regardless of amount bracket.
                //   Furniture + 800  → GATEWAY.EXIT.WW.FURNITURE.1.OUT
                //   Furniture + 2000 → GATEWAY.EXIT.WW.FURNITURE.1.OUT  (same queue)
                //   Cars      + 500  → GATEWAY.EXIT.WW.CARS.1.OUT
                // Only fires for dynamic routes — detected by HIGHVALUE/LOWVALUE
                // in the resolved routeTarget. Static routes are unaffected.
                .process(exchange -> {
                    String routeTarget = exchange.getProperty("routeTarget", String.class);
                    if (routeTarget != null
                            && (routeTarget.contains("HIGHVALUE") || routeTarget.contains("LOWVALUE"))) {
                        String payload       = exchange.getIn().getBody(String.class);
                        String typeOnlyQueue = dynamicQueueResolver.resolveTypeOnlyQueue(payload, exchange);
                        exchange.setProperty("typeOnlyQueue", typeOnlyQueue);
                    } else {
                        exchange.setProperty("typeOnlyQueue", null);
                    }
                })
                .choice()
                .when(exchange -> exchange.getProperty("typeOnlyQueue") != null)
                .wireTap("activemq:${exchangeProperty.typeOnlyQueue}")
                .end()
                .log("Type-only copy sent to ${exchangeProperty.typeOnlyQueue} (async)")
                .end()

                // ── Step 6: EXIT audit — message leaving this route leg ───────────
                // eventTimestamp IS populated — message has been forwarded
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.buildExit(ex);
                    ex.getIn().setBody(json);
                })
                .end()

                .log("EXIT audit dispatched for leg=${header.CurrentRouteName} (async)");
    }
}