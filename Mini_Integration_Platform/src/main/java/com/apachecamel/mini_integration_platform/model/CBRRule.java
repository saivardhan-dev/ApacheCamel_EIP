package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * CBRRule
 *
 * One evaluation rule inside a ServiceConfig's rules list.
 *
 * Supported comparatorTypes:
 *
 * ── STRING ────────────────────────────────────────────────────────────────
 * Reads a string field from the payload and maps it to a placeholder.
 * {
 *   "leftOperand":    "type",
 *   "comparatorType": "STRING",
 *   "operationType":  "EQ",
 *   "rightOperand":   "{TYPE}"
 * }
 *
 * ── DOUBLE ────────────────────────────────────────────────────────────────
 * Single threshold comparison — pass or throw exception.
 * {
 *   "leftOperand":    "amount",
 *   "comparatorType": "DOUBLE",
 *   "operationType":  "LT",
 *   "threshold":      5000,
 *   "onFailure":      "EXCEPTION",
 *   "failureMessage": "Amount {leftOperand} is below minimum of {threshold}"
 * }
 *
 * ── DOUBLE_RANGE ──────────────────────────────────────────────────────────
 * Checks if field value falls within [lowerBound, upperBound] (both inclusive).
 * If matched → fills rightOperand placeholder with rangeValue.
 * If not matched → skips silently (next rule evaluated).
 * {
 *   "leftOperand":    "amount",
 *   "comparatorType": "DOUBLE_RANGE",
 *   "lowerBound":     5000,
 *   "upperBound":     10000,
 *   "rangeValue":     "MIDRANGE",
 *   "rightOperand":   "{BRACKET}"
 * }
 *
 * ── DOUBLE_GT ─────────────────────────────────────────────────────────────
 * Checks if field value is strictly greater than threshold (no upper bound).
 * If matched → fills rightOperand placeholder with rangeValue.
 * If not matched → skips silently.
 * {
 *   "leftOperand":    "amount",
 *   "comparatorType": "DOUBLE_GT",
 *   "threshold":      15000,
 *   "rangeValue":     "PREMIUMPLUS",
 *   "rightOperand":   "{BRACKET}"
 * }
 *
 * Range evaluation order for amount:
 *   DOUBLE_RANGE 5000-10000  → MIDRANGE
 *   DOUBLE_RANGE 10000-15000 → PREMIUM
 *   DOUBLE_GT    15000       → PREMIUMPLUS
 *   DOUBLE LT    5000        → EXCEPTION
 */

@Data
public class CBRRule {

    /** Fully qualified class name — informational, makes JSON self-describing */
    @JsonProperty("className")
    private String className;

    /** Message field name to read from the payload e.g. "type", "amount" */
    @JsonProperty("leftOperand")
    private String leftOperand;

    /**
     * Data type and evaluation strategy.
     * Supported: "STRING", "DOUBLE", "DOUBLE_RANGE", "DOUBLE_GT"
     */
    @JsonProperty("comparatorType")
    private String comparatorType;

    /**
     * Comparison operator — used by STRING and DOUBLE rules.
     * STRING: "EQ", "NEQ"
     * DOUBLE: "EQ", "NEQ", "GTE", "GT", "LTE", "LT"
     */
    @JsonProperty("operationType")
    private String operationType;

    /**
     * Placeholder key in the queue pattern to fill with the result.
     * STRING  → filled with uppercased field value
     * DOUBLE_RANGE / DOUBLE_GT → filled with rangeValue
     * e.g. "{TYPE}", "{BRACKET}"
     */
    @JsonProperty("rightOperand")
    private String rightOperand;

    /** Single threshold — used by DOUBLE and DOUBLE_GT rules */
    @JsonProperty("threshold")
    private Double threshold;

    // ── DOUBLE_RANGE fields ───────────────────────────────────────────────────

    /** Lower bound (inclusive) for DOUBLE_RANGE rule e.g. 5000 */
    @JsonProperty("lowerBound")
    private Double lowerBound;

    /** Upper bound (inclusive) for DOUBLE_RANGE rule e.g. 10000 */
    @JsonProperty("upperBound")
    private Double upperBound;

    /**
     * Value placed into rightOperand placeholder when range/GT matches.
     * e.g. "MIDRANGE", "PREMIUM", "PREMIUMPLUS"
     */
    @JsonProperty("rangeValue")
    private String rangeValue;

    // ── DOUBLE failure fields ─────────────────────────────────────────────────

    /** Action on condition true — "PROCEED" (default for DOUBLE) */
    @JsonProperty("onSuccess")
    private String onSuccess;

    /** Action on condition false — "EXCEPTION" */
    @JsonProperty("onFailure")
    private String onFailure;

    /**
     * Exception message template when onFailure = "EXCEPTION".
     * Placeholders: {leftOperand} = actual value, {threshold} = threshold value
     */
    @JsonProperty("failureMessage")
    private String failureMessage;

    // ── Type helpers ──────────────────────────────────────────────────────────

    public boolean isStringRule() {
        return "STRING".equalsIgnoreCase(comparatorType);
    }

    public boolean isDoubleRule() {
        return "DOUBLE".equalsIgnoreCase(comparatorType);
    }

    public boolean isDoubleRangeRule() {
        return "DOUBLE_RANGE".equalsIgnoreCase(comparatorType);
    }

    public boolean isDoubleGtRule() {
        return "DOUBLE_GT".equalsIgnoreCase(comparatorType);
    }
}