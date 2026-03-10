package com.apachecamel.mini_integration_platform.config;

import com.apachecamel.mini_integration_platform.model.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ehcache.Cache;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * CacheLoader
 *
 * Runs once at application startup (@PostConstruct).
 * Reads both JSON config files from the classpath and populates EhCache.
 *
 * Root cause of the startup error:
 *   "No qualifying bean of type 'ObjectMapper' available"
 *
 * Fix applied — two changes:
 *   1. @DependsOn("objectMapper") forces Spring to initialise JacksonConfig's
 *      ObjectMapper bean BEFORE this component is constructed.
 *   2. ObjectMapper is now fetched from ApplicationContext in @PostConstruct
 *      rather than injected via constructor, eliminating the circular
 *      bean resolution timing issue entirely.
 *
 * ┌──────────────────────────────┬─────────────────────────────────────────────┐
 * │ File                         │ Loaded into                                 │
 * ├──────────────────────────────┼─────────────────────────────────────────────┤
 * │ Scenarios.json               │ scenarioCache      key = "WW_Scenario1_1"   │
 * │ ExceptionCodeList.json       │ exceptionCodeCache key = "JsonParsingException" │
 * └──────────────────────────────┴─────────────────────────────────────────────┘
 */
@Slf4j
@Component
@DependsOn("objectMapper")
@RequiredArgsConstructor
public class CacheLoader {

    private final Cache<String, Scenario> scenarioCache;
    private final Cache<String, String>   exceptionCodeCache;
    private final ApplicationContext      applicationContext;

    // ──────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void loadAllCaches() {
        // Fetch ObjectMapper after Spring context is fully ready — avoids timing issues
        ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);

        log.info("=== CacheLoader: Starting cache population ===");
        loadScenarios(objectMapper);
        loadExceptionCodes(objectMapper);
        log.info("=== CacheLoader: Cache population complete ===");
    }

    // ── Scenarios.json ─────────────────────────────────────────────────────────

    private void loadScenarios(ObjectMapper objectMapper) {
        log.info("Loading Scenarios.json into scenarioCache...");
        try (InputStream is = new ClassPathResource("Scenarios.json").getInputStream()) {

            JsonNode root      = objectMapper.readTree(is);
            JsonNode scenarios = root.get("Scenarios");

            if (scenarios == null || !scenarios.isArray()) {
                throw new IllegalStateException("Scenarios.json must have a top-level 'Scenarios' array");
            }

            int count = 0;
            for (JsonNode node : scenarios) {
                Scenario scenario = objectMapper.treeToValue(node, Scenario.class);
                String   key      = scenario.getCacheKey();

                scenarioCache.put(key, scenario);
                count++;

                log.info("  -> Cached scenario: key='{}' sourceQueue='{}'",
                        key, scenario.getEffectiveSourceQueue());
            }

            log.info("Loaded {} scenario(s) into scenarioCache", count);

        } catch (Exception e) {
            log.error("FATAL: Failed to load Scenarios.json - {}", e.getMessage(), e);
            throw new RuntimeException("Application cannot start: Scenarios.json load failed", e);
        }
    }

    // ── ExceptionCodeList.json ─────────────────────────────────────────────────

    private void loadExceptionCodes(ObjectMapper objectMapper) {
        log.info("Loading ExceptionCodeList.json into exceptionCodeCache...");
        try (InputStream is = new ClassPathResource("ExceptionCodeList.json").getInputStream()) {

            JsonNode root       = objectMapper.readTree(is);
            JsonNode errorCodes = root.get("ErrorCodes");

            if (errorCodes == null || !errorCodes.isArray()) {
                throw new IllegalStateException("ExceptionCodeList.json must have a top-level 'ErrorCodes' array");
            }

            int count = 0;
            for (JsonNode node : errorCodes) {
                String exceptionName = node.get("Exception").asText();
                String exceptionCode = node.get("ExceptionCode").asText();

                exceptionCodeCache.put(exceptionName, exceptionCode);
                count++;

                log.info("  -> Cached exception code: '{}' -> '{}'", exceptionName, exceptionCode);
            }

            log.info("Loaded {} exception code(s) into exceptionCodeCache", count);

        } catch (Exception e) {
            log.error("FATAL: Failed to load ExceptionCodeList.json - {}", e.getMessage(), e);
            throw new RuntimeException("Application cannot start: ExceptionCodeList.json load failed", e);
        }
    }
}