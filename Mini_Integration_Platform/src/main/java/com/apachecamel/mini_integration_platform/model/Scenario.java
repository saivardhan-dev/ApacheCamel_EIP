package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Maps to one Scenario object in Scenarios.json.
 *
 * Example JSON:
 * {
 *   "CountryCode":  "WW",
 *   "ScenarioName": "Scenario1",
 *   "InstanceId":   1,
 *   "SourceQueue":  "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *   "TargetQueue":  "GATEWAY.ENTRY.WW.SCENARIO1.1.OUT",
 *   "Routes": [ { "RouteName":"Route1", ... }, { "RouteName":"Route2", ... } ]
 * }
 *
 * NOTE: Scenario2 in the JSON uses "InboundQueue" instead of "SourceQueue".
 *       Both fields are mapped; getEffectiveSourceQueue() returns whichever is non-null.
 */
@Data
public class Scenario {

    @JsonProperty("CountryCode")
    private String countryCode;

    @JsonProperty("ScenarioName")
    private String scenarioName;

    @JsonProperty("InstanceId")
    private int instanceId;

    /** Used by Scenario1 */
    @JsonProperty("SourceQueue")
    private String sourceQueue;

    /** Used by Scenario2 (different key name in JSON) */
    @JsonProperty("InboundQueue")
    private String inboundQueue;

    @JsonProperty("TargetQueue")
    private String targetQueue;

    @JsonProperty("Routes")
    private List<RouteConfig> routes;

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns the correct source queue regardless of which JSON key was used.
     * Scenario1 → SourceQueue,  Scenario2 → InboundQueue
     */
    public String getEffectiveSourceQueue() {
        return (sourceQueue != null) ? sourceQueue : inboundQueue;
    }

    /**
     * EhCache key: "WW_Scenario1_1"
     * Format: {CountryCode}_{ScenarioName}_{InstanceId}
     */
    public String getCacheKey() {
        return countryCode + "_" + scenarioName + "_" + instanceId;
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









