package com.apachecamel.mini_integration_platform.config;

import com.apachecamel.mini_integration_platform.model.Scenario;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EHCacheConfig
 *
 * Creates the EhCache CacheManager and registers two named caches:
 *
 *   ┌─────────────────────────┬──────────────┬───────────────────────────────────┐
 *   │ Cache Name              │ Key          │ Value                             │
 *   ├─────────────────────────┼──────────────┼───────────────────────────────────┤
 *   │ scenarioCache           │ String       │ Scenario (from Scenarios.json)    │
 *   │ exceptionCodeCache      │ String       │ String   (exception → code map)   │
 *   └─────────────────────────┴──────────────┴───────────────────────────────────┘
 *
 * Cache keys:
 *   scenarioCache      → "WW_Scenario1_1"          (CountryCode_ScenarioName_InstanceId)
 *   exceptionCodeCache → "JsonParsingException"     (exception simple class name)
 */
@Configuration
public class EHCacheConfig {

    // ── Cache name constants — used everywhere in the codebase ─────────────────
    public static final String SCENARIO_CACHE        = "scenarioCache";
    public static final String EXCEPTION_CODE_CACHE  = "exceptionCodeCache";

    /**
     * Builds and starts the EhCache CacheManager with both caches registered.
     */
    @Bean
    public CacheManager ehCacheManager() {
        return CacheManagerBuilder.newCacheManagerBuilder()

                // ── Scenario cache ─────────────────────────────────────────────
                .withCache(
                        SCENARIO_CACHE,
                        CacheConfigurationBuilder.newCacheConfigurationBuilder(
                                String.class,       // key:   "WW_Scenario1_1"
                                Scenario.class,     // value: full Scenario object
                                ResourcePoolsBuilder.heap(50)
                        )
                )

                // ── Exception code cache ───────────────────────────────────────
                .withCache(
                        EXCEPTION_CODE_CACHE,
                        CacheConfigurationBuilder.newCacheConfigurationBuilder(
                                String.class,       // key:   "JsonParsingException"
                                String.class,       // value: "ExceptionCode1"
                                ResourcePoolsBuilder.heap(50)
                        )
                )

                .build(true); // true = initialise immediately
    }

    /**
     * Convenience bean — direct access to the Scenario cache.
     * Injected into ScenarioCacheService and routes.
     */
    @Bean
    public Cache<String, Scenario> scenarioCache(CacheManager ehCacheManager) {
        return ehCacheManager.getCache(SCENARIO_CACHE, String.class, Scenario.class);
    }

    /**
     * Convenience bean — direct access to the ExceptionCode cache.
     * Injected into ExceptionCodeService.
     */
    @Bean
    public Cache<String, String> exceptionCodeCache(CacheManager ehCacheManager) {
        return ehCacheManager.getCache(EXCEPTION_CODE_CACHE, String.class, String.class);
    }
}








