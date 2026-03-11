package com.apachecamel.mini_integration_platform.processor;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.time.Instant;

/**
 * ScenarioProcessor
 *
 * Called at the start of Route-1 (ScenarioEntryRoute) for every scenario.
 * Stamps all required headers onto the incoming exchange so that:
 *   - Route-2 knows WHERE to route the message (RoutingSlip headers)
 *   - AuditProcessor can build a complete Audit.json message
 *   - ExceptionProcessor can build a complete Exception.json message
 *
 * Headers set:
 * ┌──────────────────────────┬────────────────────────────────────────────────┐
 * │ Header                   │ Value                                          │
 * ├──────────────────────────┼────────────────────────────────────────────────┤
 * │ OriginalMessageId        │ JMS MessageId of the inbound message           │
 * │ SourcePutTimestamp       │ ISO-8601 timestamp when message was received   │
 * │ RoutingSlip_CountryCode  │ e.g. "WW"                                      │
 * │ RoutingSlip_ScenarioName │ e.g. "Scenario1"                               │
 * │ RoutingSlip_InstanceId   │ e.g. 1                                         │
 * │ ScenarioSourceQueue      │ e.g. "GATEWAY.ENTRY.WW.SCENARIO1.1.IN"         │
 * │ CurrentRouteName         │ "Route1"                                       │
 * │ RouteSource              │ Route1's source queue                          │
 * │ RouteTarget              │ Route1's target queue (CORE.ENTRY.SERVICE.IN)  │
 * │ RouteStartTimestamp      │ ISO-8601 timestamp when Route1 processing began│
 * └──────────────────────────┴────────────────────────────────────────────────┘
 *
 * NOTE: One instance of ScenarioProcessor is created per Scenario in
 *       ScenarioEntryRoute — each instance holds its own Scenario + Route1 reference.
 */
@Slf4j
public class ScenarioProcessor implements Processor {

    // ── Header name constants (shared with AuditProcessor + ExceptionProcessor) ─
    public static final String ORIGINAL_MESSAGE_ID     = "OriginalMessageId";
    public static final String SOURCE_PUT_TIMESTAMP    = "SourcePutTimestamp";
    public static final String ROUTING_SLIP_COUNTRY    = "RoutingSlip_CountryCode";
    public static final String ROUTING_SLIP_SCENARIO   = "RoutingSlip_ScenarioName";
    public static final String ROUTING_SLIP_INSTANCE   = "RoutingSlip_InstanceId";
    public static final String SCENARIO_SOURCE_QUEUE   = "ScenarioSourceQueue";
    public static final String CURRENT_ROUTE_NAME      = "CurrentRouteName";
    public static final String ROUTE_SOURCE            = "RouteSource";
    public static final String ROUTE_TARGET            = "RouteTarget";
    public static final String ROUTE_START_TIMESTAMP   = "RouteStartTimestamp";

    // ── Injected at construction time by ScenarioEntryRoute ───────────────────
    private final Scenario     scenario;
    private final RouteConfig  route1Config;

    public ScenarioProcessor(Scenario scenario, RouteConfig route1Config) {
        this.scenario    = scenario;
        this.route1Config = route1Config;
    }

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void process(Exchange exchange) {
        log.debug("[ScenarioProcessor] Processing message for scenario='{}'",
                scenario.getCacheKey());

        // ── Identity headers ────────────────────────────────────────────────────
        // Per task spec: for Route-1, OriginalMessageId IS the JMS MessageId
        String messageId = exchange.getIn().getMessageId();
        exchange.getIn().setHeader(ORIGINAL_MESSAGE_ID,  messageId);
        exchange.getIn().setHeader(SOURCE_PUT_TIMESTAMP, Instant.now().toString());

        // ── RoutingSlip headers ─────────────────────────────────────────────────
        // These travel with the message through Route-1 and are READ by Route-2
        exchange.getIn().setHeader(ROUTING_SLIP_COUNTRY,  scenario.getCountryCode());
        exchange.getIn().setHeader(ROUTING_SLIP_SCENARIO, scenario.getScenarioName());
        exchange.getIn().setHeader(ROUTING_SLIP_INSTANCE, scenario.getInstanceId());
        exchange.getIn().setHeader(SCENARIO_SOURCE_QUEUE, scenario.getEffectiveSourceQueue());

        // ── RouteInfo headers for Route-1 ───────────────────────────────────────
        exchange.getIn().setHeader(CURRENT_ROUTE_NAME,   route1Config.getRouteName()); // "Route1"
        exchange.getIn().setHeader(ROUTE_SOURCE,         route1Config.getSource());
        exchange.getIn().setHeader(ROUTE_TARGET,         route1Config.getTarget());
        exchange.getIn().setHeader(ROUTE_START_TIMESTAMP, Instant.now().toString());

        log.info("[ScenarioProcessor] Headers stamped — messageId='{}' scenario='{}' target='{}'",
                messageId, scenario.getCacheKey(), route1Config.getTarget());
    }
}
