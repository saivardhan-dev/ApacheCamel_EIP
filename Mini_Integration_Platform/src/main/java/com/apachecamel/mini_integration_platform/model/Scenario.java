package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Scenario
 *
 * Maps to one Scenario object in Scenarios.json.
 *
 * Each scenario defines:
 *   - Routing topology via the Routes array
 *   - Optional per-route services embedded inside each RouteConfig
 *
 * Scenario1 — no services, static routing:
 * {
 *   "CountryCode":  "WW",
 *   "ScenarioName": "Scenario1",
 *   "InstanceId":   1,
 *   "SourceQueue":  "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *   "TargetQueue":  "GATEWAY.ENTRY.WW.SCENARIO1.1.OUT",
 *   "Routes": [
 *     { "RouteName": "Route1", "source": "...", "target": "..." },
 *     { "RouteName": "Route2", "source": "...", "target": "..." }
 *   ]
 * }
 *
 * Scenario2 — XSLT on Route1, CBR on Route2:
 * {
 *   "Routes": [
 *     { "RouteName": "Route1", ..., "service": { "type": "XSLT", ... } },
 *     { "RouteName": "Route2", ..., "service": { "type": "DYNAMIC_TYPE_AMOUNT", ... } }
 *   ]
 * }
 */
@Data
public class Scenario {

    @JsonProperty("CountryCode")
    private String countryCode;

    @JsonProperty("ScenarioName")
    private String scenarioName;

    @JsonProperty("InstanceId")
    private int instanceId;

    @JsonProperty("SourceQueue")
    private String sourceQueue;

    /** Alternative key used by some scenarios */
    @JsonProperty("InboundQueue")
    private String inboundQueue;

    @JsonProperty("TargetQueue")
    private String targetQueue;

    @JsonProperty("Routes")
    private List<RouteConfig> routes;

    /**
     * Optional splitter config — present only when this scenario
     * accepts multi-message envelope payloads.
     * Null for scenarios that only accept single messages.
     */
    @JsonProperty("splitter")
    private SplitterConfig splitter;

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns the correct source queue regardless of which JSON key was used.
     */
    public String getEffectiveSourceQueue() {
        return (sourceQueue != null) ? sourceQueue : inboundQueue;
    }

    /**
     * EhCache key — format: {CountryCode}_{ScenarioName}_{InstanceId}
     * e.g. "WW_Scenario1_1"
     */
    public String getCacheKey() {
        return countryCode + "_" + scenarioName + "_" + instanceId;
    }

    /** Returns true if this scenario has an active splitter configured */
    public boolean hasSplitter() {
        return splitter != null && splitter.isActive();
    }

    /**
     * Finds a RouteConfig by name (case-insensitive).
     * e.g. findRoute("Route1"), findRoute("Route2")
     */
    public RouteConfig findRoute(String routeName) {
        if (routes == null) return null;
        return routes.stream()
                .filter(r -> r.getRouteName().equalsIgnoreCase(routeName))
                .findFirst()
                .orElse(null);
    }
}