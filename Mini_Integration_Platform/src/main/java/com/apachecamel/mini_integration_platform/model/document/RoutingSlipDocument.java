package com.apachecamel.mini_integration_platform.model.document;

import lombok.Builder;
import lombok.Data;

/**
 * Embedded document — maps to the RoutingSlip block inside Audit.json / Exception.json.
 *
 * Stored as a nested object inside AuditDocument and ExceptionDocument.
 * NOT a top-level MongoDB collection.
 *
 * JSON shape:
 * {
 *   "CountryCode":  "WW",
 *   "ScenarioName": "Scenario1",
 *   "InstanceId":   1
 * }
 */
@Data
@Builder
public class RoutingSlipDocument {
    private String countryCode;
    private String scenarioName;
    private int    instanceId;
}