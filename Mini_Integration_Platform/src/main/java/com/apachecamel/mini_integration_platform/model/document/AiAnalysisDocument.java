package com.apachecamel.mini_integration_platform.model.document;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * AiAnalysisDocument
 *
 * Embedded sub-document stored inside ExceptionDocument.aiAnalysis.
 * Populated asynchronously by ExceptionAnalysisService after the
 * exception is saved to MongoDB.
 *
 * MongoDB shape:
 * "aiAnalysis": {
 *   "summary":           "The message payload contained type=ERROR which triggered...",
 *   "rootCause":         "MessageValidatorProcessor intentionally rejects messages...",
 *   "affectedComponent": "MessageValidatorProcessor",
 *   "suggestedAction":   "Review upstream producer to ensure type field is valid...",
 *   "severity":          "MEDIUM",
 *   "analysedAt":        ISODate
 * }
 */
@Data
@Builder
public class AiAnalysisDocument {

  @Field("summary")
  private String summary;

  @Field("rootCause")
  private String rootCause;

  @Field("affectedComponent")
  private String affectedComponent;

  @Field("suggestedAction")
  private String suggestedAction;

  /** LOW / MEDIUM / HIGH / CRITICAL */
  @Field("severity")
  private String severity;

  /** Timestamp when the AI analysis was completed */
  @Field("analysedAt")
  private Instant analysedAt;
}