package com.apachecamel.mini_integration_platform.model.document;

import lombok.Builder;
import lombok.Data;

/**
 * Embedded document — maps to the RouteInfo block inside Audit.json / Exception.json.
 *
 * Stored as a nested object inside AuditDocument and ExceptionDocument.
 *
 * JSON shape:
 * {
 *   "RouteName":      "Route1",
 *   "source":         "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *   "target":         "CORE.ENTRY.SERVICE.IN",
 *   "startTimestamp": "2026-03-10T14:00:00.010Z",
 *   "endTimestamp":   "2026-03-10T14:00:00.050Z"   ← empty string for exceptions
 * }
 */
@Data
@Builder
public class RouteInfoDocument {
    private String routeName;
    private String source;
    private String target;
    private String startTimestamp;
    private String endTimestamp;
}