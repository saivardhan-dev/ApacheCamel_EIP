package com.apachecamel.mini_integration_platform.model.document;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * WireTapDocument
 *
 * MongoDB document stored in the "wiretaps" collection.
 * Captures a snapshot of every message at the exact point it was tapped —
 * raw body + all headers at that moment in the flow.
 *
 * MongoDB collection: wiretaps
 *
 * Document structure:
 * {
 *   "_id":               ObjectId,
 *   "tapPoint":          "ROUTE1_ENTRY",   ← where in the flow this was tapped
 *   "originalMessageId": "ID:abc-123",
 *   "messageId":         "ID:abc-123",
 *   "scenarioName":      "Scenario1",
 *   "countryCode":       "WW",
 *   "instanceId":        1,
 *   "routeName":         "Route1",
 *   "sourceQueue":       "GATEWAY.ENTRY.WW.SCENARIO1.1.IN",
 *   "targetQueue":       "CORE.ENTRY.SERVICE.IN",
 *   "payload":           "{ original body }",
 *   "tappedAt":          ISODate
 * }
 */
@Data
@Builder
@Document(collection = "wiretaps")
public class WireTapDocument {

  @Id
  private String id;

  /** Where in the flow this was tapped — e.g. ROUTE1_ENTRY, ROUTE2_POST_VALIDATION, EXIT */
  @Indexed
  @Field("tapPoint")
  private String tapPoint;

  @Indexed
  @Field("originalMessageId")
  private String originalMessageId;

  @Field("messageId")
  private String messageId;

  @Indexed
  @Field("scenarioName")
  private String scenarioName;

  @Field("countryCode")
  private String countryCode;

  @Field("instanceId")
  private int instanceId;

  @Field("routeName")
  private String routeName;

  @Field("sourceQueue")
  private String sourceQueue;

  @Field("targetQueue")
  private String targetQueue;

  @Field("payload")
  private String payload;

  /** Exact timestamp when this wire tap snapshot was taken */
  @Field("tappedAt")
  private Instant tappedAt;
}