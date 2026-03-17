package com.apachecamel.mini_integration_platform.model.document;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * AuditDocument
 *
 * MongoDB document stored in the "audits" collection.
 * Maps exactly to the Audit.json message structure produced by AuditProcessor.
 *
 * MongoDB collection: audits
 * Database:           mini_integration_platform
 *
 * Document structure:
 * {
 *   "_id":                ObjectId (auto-generated),
 *   "originalMessageId":  "ID:abc-123",
 *   "sourcePutTimestamp": "2026-03-10T14:00:00Z",
 *   "eventTimestamp":     "2026-03-10T14:00:00.050Z",
 *   "messageId":          "ID:abc-123",
 *   "auditQueue":         "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *   "routingSlip": {
 *       "countryCode":  "WW",
 *       "scenarioName": "Scenario1",
 *       "instanceId":   1
 *   },
 *   "routeInfo": {
 *       "routeName":      "Route1",
 *       "source":         "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *       "target":         "CORE.ENTRY.SERVICE.IN",
 *       "startTimestamp": "...",
 *       "endTimestamp":   "..."
 *   },
 *   "payload":            "{ original message body }",
 *   "createdAt":          ISODate  (server-side insert timestamp)
 * }
 *
 * Indexes:
 *   - originalMessageId  (for fast lookup by message chain)
 *   - routingSlip.scenarioName + routeInfo.routeName  (for per-scenario/route queries)
 */
@Data
@Builder
@Document(collection = "audits")
public class AuditDocument {

    @Id
    private String id;

    @Indexed
    @Field("originalMessageId")
    private String originalMessageId;

    @Field("sourcePutTimestamp")
    private String sourcePutTimestamp;

    @Field("eventTimestamp")
    private String eventTimestamp;

    @Indexed
    @Field("messageId")
    private String messageId;

    @Field("auditQueue")
    private String auditQueue;

    @Field("routingSlip")
    private RoutingSlipDocument routingSlip;

    @Field("routeInfo")
    private RouteInfoDocument routeInfo;

    @Field("payload")
    private String payload;

    /** Server-side timestamp — when this document was inserted into MongoDB */
    /** ENTRY — message entering the route, EXIT — message leaving the route */
    @Indexed
    @Field("auditType")
    private String auditType;

    @Field("createdAt")
    private Instant createdAt;
}