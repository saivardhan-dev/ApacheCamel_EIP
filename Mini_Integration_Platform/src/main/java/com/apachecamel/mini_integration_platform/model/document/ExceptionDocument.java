package com.apachecamel.mini_integration_platform.model.document;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * ExceptionDocument
 *
 * MongoDB document stored in the "exceptions" collection.
 * Maps exactly to the Exception.json message structure produced by ExceptionProcessor.
 *
 * MongoDB collection: exceptions
 * Database:           mini_integration_platform
 *
 * Document structure:
 * {
 *   "_id":                  ObjectId (auto-generated),
 *   "originalMessageId":    "ID:abc-123",
 *   "sourcePutTimestamp":   "2026-03-10T14:00:00Z",
 *   "eventTimestamp":       "2026-03-10T14:00:00.090Z",
 *   "messageId":            "ID:abc-123",
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
 *       "endTimestamp":   ""        ← always empty for exceptions
 *   },
 *   "payload":              "{ original message body }",
 *   "exceptionCode":        "ExceptionCode3",
 *   "exceptionStacktrace":  "java.lang.RuntimeException: ...",
 *   "createdAt":            ISODate
 * }
 *
 * Indexes:
 *   - originalMessageId   (correlate with audit records for the same message)
 *   - exceptionCode       (filter by error type)
 */
@Data
@Builder
@Document(collection = "exceptions")
public class ExceptionDocument {

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

    @Field("routingSlip")
    private RoutingSlipDocument routingSlip;

    @Field("routeInfo")
    private RouteInfoDocument routeInfo;

    @Field("payload")
    private String payload;

    @Indexed
    @Field("exceptionCode")
    private String exceptionCode;

    @Field("exceptionStacktrace")
    private String exceptionStacktrace;

    /** Server-side timestamp — when this document was inserted into MongoDB */
    @Field("createdAt")
    private Instant createdAt;
}