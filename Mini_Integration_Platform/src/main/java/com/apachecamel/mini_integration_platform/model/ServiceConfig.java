package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * ServiceConfig
 *
 * Represents an inline service on a route leg in Scenarios.json.
 * Each service is backed by a className for self-documentation and
 * carries a list of CBRRule entries that drive the routing decision.
 *
 * ── XSLT service ─────────────────────────────────────────────────────────
 * {
 *   "className":  "com.apachecamel.mini_integration_platform.model.ServiceConfig",
 *   "serviceId":  "XSLT_TRANSFORM",
 *   "type":       "XSLT",
 *   "enabled":    true,
 *   "xsltPath":   "xslt/xml-to-json.xsl"
 * }
 *
 * ── CBR service ───────────────────────────────────────────────────────────
 * {
 *   "className":    "com.apachecamel.mini_integration_platform.model.ServiceConfig",
 *   "serviceId":    "CBR_ROUTING",
 *   "type":         "DYNAMIC_TYPE_AMOUNT",
 *   "enabled":      true,
 *   "queuePattern": "GATEWAY.EXIT.WW.{SCENARIO}.{TYPE}.1.OUT",
 *   "rules": [
 *     {
 *       "className":      "com.apachecamel.mini_integration_platform.model.CBRRule",
 *       "leftOperand":    "type",
 *       "comparatorType": "STRING",
 *       "operationType":  "EQ",
 *       "rightOperand":   "{TYPE}"
 *     },
 *     {
 *       "className":      "com.apachecamel.mini_integration_platform.model.CBRRule",
 *       "leftOperand":    "amount",
 *       "comparatorType": "DOUBLE",
 *       "operationType":  "GTE",
 *       "threshold":      25000,
 *       "onSuccess":      "PROCEED",
 *       "onFailure":      "EXCEPTION",
 *       "failureMessage": "Amount {leftOperand} fails GTE check against threshold {threshold}"
 *     }
 *   ]
 * }
 */
@Data
public class ServiceConfig {

    /**
     * Fully qualified class name — informational, makes JSON self-describing.
     * e.g. "com.apachecamel.mini_integration_platform.model.ServiceConfig"
     */
    @JsonProperty("className")
    private String className;

    /** Unique identifier for this service within the scenario */
    @JsonProperty("serviceId")
    private String serviceId;

    /**
     * Service type.
     * Supported: "XSLT", "DYNAMIC_TYPE_AMOUNT"
     */
    @JsonProperty("type")
    private String type;

    /** Whether this service is active. False = skip silently. */
    @JsonProperty("enabled")
    private boolean enabled;

    // ── XSLT fields ───────────────────────────────────────────────────────────

    /**
     * Path to the XSLT stylesheet — relative to src/main/resources.
     * Only used when type = "XSLT".
     */
    @JsonProperty("xsltPath")
    private String xsltPath;

    // ── CBR fields ─────────────────────────────────────────────────────────────

    /**
     * Queue name pattern for dynamic routing.
     * Placeholders filled by STRING rules: {SCENARIO}, {TYPE}
     * e.g. "GATEWAY.EXIT.WW.{SCENARIO}.{TYPE}.1.OUT"
     */
    @JsonProperty("queuePattern")
    private String queuePattern;


    /**
     * List of CBRRule entries that drive the routing decision.
     * Evaluated in order:
     *   STRING rules → populate placeholders in queuePattern
     *   DOUBLE rules → pass silently or throw exception
     */
    @JsonProperty("rules")
    private List<CBRRule> rules;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isXslt() {
        return enabled && "XSLT".equalsIgnoreCase(type);
    }

    public boolean isDynamicTypeAmount() {
        return enabled && "DYNAMIC_TYPE_AMOUNT".equalsIgnoreCase(type);
    }
}