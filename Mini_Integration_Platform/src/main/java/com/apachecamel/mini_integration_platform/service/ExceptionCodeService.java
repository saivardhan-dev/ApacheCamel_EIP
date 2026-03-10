package com.apachecamel.mini_integration_platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ehcache.Cache;
import org.springframework.stereotype.Service;

/**
 * ExceptionCodeService
 *
 * Resolves the correct ExceptionCode string from the exceptionCodeCache
 * for any given Throwable.
 *
 * Resolution order:
 *   1. Exact match on thrown.getClass().getSimpleName()
 *      e.g. "JsonParsingException" → "ExceptionCode1"
 *   2. Exact match on thrown.getCause().getClass().getSimpleName()
 *   3. Generic fallback: cache key "Exception" → "ExceptionCode3"
 *   4. Last resort: returns literal "UNKNOWN_CODE"
 *
 * Cache is loaded from ExceptionCodeList.json at startup by CacheLoader.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionCodeService {

    private final Cache<String, String> exceptionCodeCache;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Resolves an ExceptionCode for the given Throwable.
     *
     * @param thrown  the exception that was caught (maybe null)
     * @return the matching ExceptionCode string, never null
     */
    public String resolveCode(Throwable thrown) {
        if (thrown == null) {
            return "UNKNOWN_CODE";
        }

        // 1. Try exact class name
        String code = lookupByClassName(thrown.getClass().getSimpleName());
        if (code != null) {
            log.debug("Resolved exception code '{}' for class '{}'", code, thrown.getClass().getSimpleName());
            return code;
        }

        // 2. Try cause class name
        if (thrown.getCause() != null) {
            code = lookupByClassName(thrown.getCause().getClass().getSimpleName());
            if (code != null) {
                log.debug("Resolved exception code '{}' via cause '{}'",
                        code, thrown.getCause().getClass().getSimpleName());
                return code;
            }
        }

        // 3. Generic fallback — "Exception" key in ExceptionCodeList.json → "ExceptionCode3"
        code = lookupByClassName("Exception");
        if (code != null) {
            log.debug("Using generic fallback exception code '{}'", code);
            return code;
        }

        // 4. Last resort
        log.warn("No exception code found for '{}' — returning UNKNOWN_CODE",
                thrown.getClass().getSimpleName());
        return "UNKNOWN_CODE";
    }

    // ── Internal ────────────────────────────────────────────────────────────────

    private String lookupByClassName(String simpleClassName) {
        return exceptionCodeCache.get(simpleClassName);
    }
}









