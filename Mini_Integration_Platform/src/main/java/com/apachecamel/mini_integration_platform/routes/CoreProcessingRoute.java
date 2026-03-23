package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.model.ServiceConfig;
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
 * CoreProcessingRoute
 *
 * Handles ALL route legs after Route-1 for every scenario.
 * Shared single route — all scenarios pass through here.
 *
 * Services are resolved from the Scenario's Services array at runtime:
 *   scenario.findService("Route2") → ServiceConfig
 *
 * If a DYNAMIC_TYPE_AMOUNT service is found → DynamicQueueResolver resolves queue.
 * If no service found → static target from RouteConfig is used.
 *
 * Step order:
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Step 1a: EhCache lookup → stamp RouteInfo headers                   │
 * │  Step 2:  ENTRY audit wireTap   ← always fires                      │
 * │  Step 3:  Service lookup → resolve target queue (CBR or static)      │
 * │  Step 4:  MessageValidatorProcessor                                  │
 * │  Step 5:  .toD(routeTarget)     ← forward message                   │
 * │  Step 6:  Type-only wireTap     ← only if CBR service configured    │
 * │  Step 7:  EXIT audit wireTap                                         │
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

    @Value("${app.queue.dlq:COMMON.DLQ.SERVICE.IN}")
    private String dlqQueue;



    private static final String PROP_CURRENT_LEG_INDEX = "CurrentRouteLegIndex";

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void configure() {
        log.info("[CoreProcessingRoute] Registering core handler on '{}'", coreEntryQueue);

        from("activemq:" + coreEntryQueue)
                .routeId("route-core-processing")

                // ── Exception handler ─────────────────────────────────────────────
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
                .log("Exception + DLQ dispatched for leg=${header.CurrentRouteName} (async)")
                .end()

                // ── Step 1a: EhCache lookup → stamp RouteInfo headers ─────────────
                // DynamicQueueResolver NOT called here — ENTRY audit must fire first.
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

                    // Stamp RouteInfo headers
                    exchange.getIn().setHeader(ScenarioProcessor.CURRENT_ROUTE_NAME,    nextLeg.getRouteName());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_SOURCE,          nextLeg.getSource());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET,          nextLeg.getTarget());
                    exchange.getIn().setHeader(ScenarioProcessor.ROUTE_START_TIMESTAMP, Instant.now().toString());
                    exchange.setProperty(PROP_CURRENT_LEG_INDEX, nextLegIndex);

                    // Store leg config and static target for use in Steps 3 and 6
                    exchange.setProperty("currentLegConfig",  nextLeg);
                    exchange.setProperty("currentRouteName",  nextLeg.getRouteName());
                    exchange.setProperty("currentLegTarget",  nextLeg.getTarget());
                })

                .log("Processing leg=${header.CurrentRouteName} scenario=${header.RoutingSlip_ScenarioName}")

                // ── Step 2: ENTRY audit ───────────────────────────────────────────
                // Fires BEFORE service resolution — guaranteed even if CBR throws.
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.buildEntry(ex);
                    ex.getIn().setBody(json);
                })
                .end()
                .log("ENTRY audit dispatched for leg=${header.CurrentRouteName} (async)")

                // ── Step 3: Validate + deserialise message ────────────────────────
                // MessageValidatorProcessor deserialises the JSON body into a
                // MessagePayload object and stores it on the exchange as property
                // "messagePayload". DynamicQueueResolver reads it in Step 4.
                // Must run BEFORE the resolver — resolver depends on this property.
                .process(messageValidatorProcessor)

                // ── Step 4: Service — read inline from RouteConfig ───────────────
                // Reads MessagePayload from exchange property set in Step 3.
                // Scenario1 Route2: service = null → use static target
                // Scenario2 Route2: service.type = DYNAMIC_TYPE_AMOUNT → CBR
                .process(exchange -> {
                    RouteConfig currentLeg   = exchange.getProperty("currentLegConfig",  RouteConfig.class);
                    String      staticTarget = exchange.getProperty("currentLegTarget",   String.class);

                    ServiceConfig service = currentLeg.getService();

                    if (service != null && service.isDynamicTypeAmount()) {
                        // CBR service found — resolve queue from rules
                        // Returns null if one or more placeholders could not be filled
                        // because the required fields were absent from the payload
                        String resolved = dynamicQueueResolver.resolve(service, exchange);

                        if (resolved == null) {
                            // Unresolved placeholders — payload fields did not match rules
                            // Route to DLQ — full message stored, no consumer
                            log.warn("[CoreProcessingRoute] CBR could not resolve queue — " +
                                    "routing to DLQ: {}", dlqQueue);
                            exchange.setProperty("routeTarget", dlqQueue);
                            exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET, dlqQueue);
                            // Also send to exception processing for MongoDB + AI
                            exchange.setProperty("sendToExceptionProcessing", true);
                        } else {
                            exchange.setProperty("routeTarget", resolved);
                            exchange.getIn().setHeader(ScenarioProcessor.ROUTE_TARGET, resolved);
                            log.info("[CoreProcessingRoute] CBR resolved — service='{}' queue='{}'",
                                    service.getServiceId(), resolved);
                        }
                    } else {
                        // No CBR service — use static target from RouteConfig
                        exchange.setProperty("routeTarget", staticTarget);
                        log.info("[CoreProcessingRoute] Static routing — target='{}'", staticTarget);
                    }
                })

                // ── Step 5: Forward to resolved target queue ✅ ───────────────────
                .toD("activemq:${exchangeProperty.routeTarget}")
                .log("Delivered to ${exchangeProperty.routeTarget} — leg=${header.CurrentRouteName}")

                // ── Step 5b: CBR unroutable → send to exception queue ────────────
                // When DynamicQueueResolver returns null, routeTarget = DLQ and
                // sendToExceptionProcessing = true. The message went to DLQ in
                // Step 5 — this wireTap also sends the structured exception JSON
                // to COMMON.EXCEPTION.SERVICE.IN so ExceptionRoute can persist
                // to MongoDB and trigger AI analysis + email.
                // This path does NOT go through onException so the wireTap must
                // be explicit here.
                .choice()
                .when(exchange -> Boolean.TRUE.equals(
                        exchange.getProperty("sendToExceptionProcessing", Boolean.class)))
                .wireTap("activemq:" + exceptionQueue)
                .onPrepare(ex -> {
                    String json = exceptionJsonBuilder.build(ex);
                    ex.getIn().setBody(json);
                })
                .end()
                .log("CBR unroutable — exception dispatched to ExceptionRoute (async)")
                .end()

                // ── Step 6: EXIT audit — only fires on successful routing ──────────
                // Guarded by routeTarget check — if message went to DLQ it means
                // CBR could not resolve a normal exit queue so EXIT audit is skipped.
                // onException path never reaches here — Camel stops after onException.
                .choice()
                .when(exchange -> {
                    String target = exchange.getProperty("routeTarget", String.class);
                    String dlq    = dlqQueue;
                    return target != null && !target.equals(dlq);
                })
                .wireTap("activemq:" + auditQueue)
                .onPrepare(ex -> {
                    String json = auditJsonBuilder.buildExit(ex);
                    ex.getIn().setBody(json);
                })
                .end()
                .log("EXIT audit dispatched for leg=${header.CurrentRouteName} (async)")
                .end();
    }
}