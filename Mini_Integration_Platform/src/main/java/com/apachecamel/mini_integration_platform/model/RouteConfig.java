package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * RouteConfig
 *
 * Maps to one entry in the "Routes" array of a Scenario in Scenarios.json.
 *
 * Each route carries an optional inline "service" block — present only
 * when a service is configured for that specific route leg.
 *
 * ── Scenario1 Route1 — no service ────────────────────────────────────────
 * {
 *   "RouteName": "Route1",
 *   "source":    "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *   "target":    "CORE.ENTRY.SERVICE.IN"
 * }
 *
 * ── Scenario2 Route1 — XSLT service ──────────────────────────────────────
 * {
 *   "RouteName": "Route1",
 *   "source":    "GATEWAY.ENTRY.WW.SCENARIO2.1.IN",
 *   "target":    "CORE.ENTRY.SERVICE.IN",
 *   "service": {
 *     "serviceId": "XSLT_TRANSFORM",
 *     "type":      "XSLT",
 *     "enabled":   true,
 *     "xsltPath":  "xslt/xml-to-json.xsl"
 *   }
 * }
 *
 * ── Scenario2 Route2 — CBR service ───────────────────────────────────────
 * {
 *   "RouteName": "Route2",
 *   "source":    "CORE.ENTRY.SERVICE.IN",
 *   "target":    null,
 *   "service": {
 *     "serviceId":            "CBR_ROUTING",
 *     "type":                 "DYNAMIC_TYPE_AMOUNT",
 *     "enabled":              true,
 *     "amountThreshold":      1500,
 *     "highValueSuffix":      "HIGHVALUE",
 *     "lowValueSuffix":       "LOWVALUE",
 *     "queuePattern":         "GATEWAY.EXIT.WW.{SCENARIO}.{TYPE}.{BRACKET}.1.OUT",
 *     "typeOnlyQueuePattern": "GATEWAY.EXIT.WW.{SCENARIO}.{TYPE}.1.OUT"
 *   }
 * }
 */
@Data
public class RouteConfig {

    @JsonProperty("RouteName")
    private String routeName;

    @JsonProperty("source")
    private String source;

    /**
     * Static target queue.
     * Null when service type is DYNAMIC_TYPE_AMOUNT —
     * target is resolved at runtime by DynamicQueueResolver.
     */
    @JsonProperty("target")
    private String target;

    /**
     * Optional inline service for this route leg.
     * Null for routes with no service configured (e.g. Scenario1).
     * Read by ScenarioEntryRoute (Route1) and CoreProcessingRoute (Route2+).
     */
    @JsonProperty("service")
    private ServiceConfig service;

    /** Returns true if this route has an enabled service configured */
    public boolean hasService() {
        return service != null && service.isEnabled();
    }
}