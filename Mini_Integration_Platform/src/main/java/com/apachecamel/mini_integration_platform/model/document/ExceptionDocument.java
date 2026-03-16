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
 *
 * Document structure:
 * {
 *   "_id":                  ObjectId,
 *   "originalMessageId":    "ID:abc-123",
 *   "sourcePutTimestamp":   "2026-03-10T14:00:00Z",
 *   "eventTimestamp":       "2026-03-10T14:00:00.090Z",
 *   "messageId":            "ID:abc-123",
 *   "routingSlip": { "countryCode", "scenarioName", "instanceId" },
 *   "routeInfo":   { "routeName", "source", "target", "startTimestamp", "endTimestamp": "" },
 *   "payload":              "{ original message body }",
 *   "exceptionCode":        "ExceptionCode3",
 *   "exceptionStacktrace":  "java.lang.RuntimeException: ...",
 *   "createdAt":            ISODate,
 *
 *   // ── AI Analysis (populated async after save) ──────────────────────────
 *   "aiAnalysis": {
 *     "summary":            "Plain English explanation of what went wrong",
 *     "rootCause":          "The specific technical reason",
 *     "affectedComponent":  "MessageValidatorProcessor",
 *     "suggestedAction":    "What the developer or ops team should do",
 *     "severity":           "LOW / MEDIUM / HIGH / CRITICAL",
 *     "analysedAt":         ISODate
 *   }
 * }
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

    @Field("createdAt")
    private Instant createdAt;


}