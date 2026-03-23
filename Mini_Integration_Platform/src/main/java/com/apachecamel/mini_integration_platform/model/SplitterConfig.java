package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * SplitterConfig
 *
 * Defines the envelope handling behaviour for a scenario.
 * Supports two modes — both fully config-driven from Scenarios.json:
 *
 * ── SPLIT mode (array envelope) ───────────────────────────────────────────
 * Input:
 * {
 *   "ApplicationID": "ID-001",
 *   "Messages": [
 *     { "type": "Cars", "amount": 5000 },
 *     { "type": "Electronics", "amount": 12000 }
 *   ]
 * }
 * Each array item becomes an independent exchange.
 *
 * ── UNWRAP mode (single object envelope) ──────────────────────────────────
 * Input:
 * {
 *   "ApplicationID": "ID-001",
 *   "Messages": { "type": "Cars", "amount": 800 }
 * }
 * The Messages object is extracted and used as the message body.
 * ApplicationID is stamped as a header.
 *
 * Scenarios.json config:
 * {
 *   "splitter": {
 *     "enabled":       true,
 *     "messagesField": "Messages",
 *     "appIdField":    "ApplicationID"
 *   }
 * }
 *
 * Mode is auto-detected at runtime:
 *   messagesField value is List → SPLIT
 *   messagesField value is Map  → UNWRAP
 *   messagesField missing       → error if appIdField present, else passthrough
 */
@Data
public class SplitterConfig {

    @JsonProperty("enabled")
    private boolean enabled;

    /**
     * JSON key holding the message(s).
     * Array value → SPLIT mode.
     * Object value → UNWRAP mode.
     * e.g. "Messages", "items", "records"
     */
    @JsonProperty("messagesField")
    private String messagesField;

    /**
     * JSON key holding the batch/app identifier.
     * Stamped as a header on every message for traceability.
     * e.g. "ApplicationID", "AppID", "batchId"
     */
    @JsonProperty("appIdField")
    private String appIdField;

    public boolean isActive() {
        return enabled
                && messagesField != null && !messagesField.isBlank()
                && appIdField    != null && !appIdField.isBlank();
    }
}