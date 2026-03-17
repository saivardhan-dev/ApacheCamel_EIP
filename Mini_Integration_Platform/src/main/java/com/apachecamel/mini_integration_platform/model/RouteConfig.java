package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * RouteConfig
 *
 * Maps to one entry inside the "Routes" array in Scenarios.json.
 *
 * ── Simple route (Route1 — static target) ────────────────────────────────
 * {
 *   "RouteName": "Route1",
 *   "source":    "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *   "target":    "CORE.ENTRY.SERVICE.IN"
 * }
 *
 * ── Dynamic routing route (Route2 — queue built from message content) ────
 * {
 *   "RouteName":        "Route2",
 *   "source":           "CORE.ENTRY.SERVICE.IN",
 *   "target":           null,
 *   "routingStrategy":  "DYNAMIC_TYPE_AMOUNT",
 *   "amountThreshold":  1500,
 *   "highValueSuffix":  "HIGHVALUE",
 *   "lowValueSuffix":   "LOWVALUE",
 *   "queuePattern":     "GATEWAY.EXIT.WW.{TYPE}.{BRACKET}.1.OUT"
 * }
 *
 * When routingStrategy = "DYNAMIC_TYPE_AMOUNT":
 *   CoreProcessingRoute delegates to DynamicQueueResolver which builds
 *   the target queue name at runtime from the message payload:
 *
 *   type=Cars,        amount=2000  → GATEWAY.EXIT.WW.CARS.HIGHVALUE.1.OUT
 *   type=Electronics, amount=999   → GATEWAY.EXIT.WW.ELECTRONICS.LOWVALUE.1.OUT
 *   type=Furniture,   amount=3000  → GATEWAY.EXIT.WW.FURNITURE.HIGHVALUE.1.OUT
 *
 * Any non-blank string type is accepted. Queue is created dynamically
 * on ActiveMQ the first time a message is sent to it.
 */
@Data
public class RouteConfig {

    @JsonProperty("RouteName")
    private String routeName;

    @JsonProperty("source")
    private String source;

    /**
     * Static target queue — used when routingStrategy is null.
     * Set to null for dynamic routing routes.
     */
    @JsonProperty("target")
    private String target;

    /**
     * Routing strategy identifier.
     * Currently supported: "DYNAMIC_TYPE_AMOUNT"
     * Null for simple static routes.
     */
    @JsonProperty("routingStrategy")
    private String routingStrategy;

    /**
     * Amount threshold for DYNAMIC_TYPE_AMOUNT strategy.
     * amount >= amountThreshold → highValueSuffix
     * amount <  amountThreshold → lowValueSuffix
     */
    @JsonProperty("amountThreshold")
    private Double amountThreshold;

    /** Suffix appended to queue name when amount >= amountThreshold. e.g. "HIGHVALUE" */
    @JsonProperty("highValueSuffix")
    private String highValueSuffix;

    /** Suffix appended to queue name when amount < amountThreshold. e.g. "LOWVALUE" */
    @JsonProperty("lowValueSuffix")
    private String lowValueSuffix;

    /**
     * Queue name pattern for dynamic routing.
     * Placeholders: {TYPE} → uppercased type field, {BRACKET} → HIGH/LOW suffix
     * e.g. "GATEWAY.EXIT.WW.{TYPE}.{BRACKET}.1.OUT"
     */
    @JsonProperty("queuePattern")
    private String queuePattern;

    /** Returns true if this route uses dynamic type+amount routing */
    public boolean isDynamicTypeAmount() {
        return "DYNAMIC_TYPE_AMOUNT".equals(routingStrategy);
    }
}