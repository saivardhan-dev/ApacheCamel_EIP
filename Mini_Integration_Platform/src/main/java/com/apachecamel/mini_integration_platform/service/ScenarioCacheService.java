package com.apachecamel.mini_integration_platform.service;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.apachecamel.mini_integration_platform.model.Scenario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ehcache.Cache;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ScenarioCacheService
 *
 * Provides clean, high-level access to the scenarioCache.
 * All routes and processors go through this service — they never
 * touch EhCache directly. This keeps cache logic in one place.
 *
 * Key format: "WW_Scenario1_1"  →  {CountryCode}_{ScenarioName}_{InstanceId}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioCacheService {

    private final Cache<String, Scenario> scenarioCache;

    // ── Lookups ────────────────────────────────────────────────────────────────

    /**
     * Fetches a Scenario by its composite cache key.
     *
     * @param countryCode   e.g. "WW"
     * @param scenarioName  e.g. "Scenario1"
     * @param instanceId    e.g. 1
     * @return the Scenario, or null if not found
     */
    public Scenario getScenario(String countryCode, String scenarioName, int instanceId) {
        String key = buildKey(countryCode, scenarioName, instanceId);
        Scenario scenario = scenarioCache.get(key);
        if (scenario == null) {
            log.warn("No scenario found in cache for key='{}'", key);
        }
        return scenario;
    }

    /**
     * Fetches a Scenario directly by its pre-formed cache key.
     *
     * @param cacheKey  e.g. "WW_Scenario1_1"
     */
    public Scenario getScenarioByKey(String cacheKey) {
        Scenario scenario = scenarioCache.get(cacheKey);
        if (scenario == null) {
            log.warn("No scenario found in cache for key='{}'", cacheKey);
        }
        return scenario;
    }

    /**
     * Returns all scenarios currently loaded in the cache.
     * Used by route builders to iterate and create one route per scenario.
     */
    public List<Scenario> getAllScenarios() {
        List<Scenario> list = new ArrayList<>();
        for (Cache.Entry<String, Scenario> entry : scenarioCache) {
            list.add(entry.getValue());
        }
        log.debug("getAllScenarios() returning {} scenario(s)", list.size());
        return list;
    }

    /**
     * Finds the Route1 definition for a given scenario.
     *
     * @return RouteConfig for Route1, or null if not defined
     */
    public RouteConfig getRoute1(String countryCode, String scenarioName, int instanceId) {
        Scenario scenario = getScenario(countryCode, scenarioName, instanceId);
        if (scenario == null) return null;
        return scenario.findRoute("Route1");
    }

    /**
     * Finds the Route2 definition for a given scenario.
     *
     * @return RouteConfig for Route2, or null if not defined
     */
    public RouteConfig getRoute2(String countryCode, String scenarioName, int instanceId) {
        Scenario scenario = getScenario(countryCode, scenarioName, instanceId);
        if (scenario == null) return null;
        return scenario.findRoute("Route2");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    public static String buildKey(String countryCode, String scenarioName, int instanceId) {
        return countryCode + "_" + scenarioName + "_" + instanceId;
    }
}









