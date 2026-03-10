package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Maps to one entry inside the "Routes" array of a Scenario in Scenarios.json.
 *
 * Example JSON:
 * {
 *   "RouteName": "Route1",
 *   "source":    "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *   "target":    "CORE.ENTRY.SERVICE.IN"
 * }
 */
@Data
public class RouteConfig {

    @JsonProperty("RouteName")
    private String routeName;

    @JsonProperty("source")
    private String source;

    @JsonProperty("target")
    private String target;
}

