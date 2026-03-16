package com.apachecamel.mini_integration_platform.service.ai;

import com.apachecamel.mini_integration_platform.model.document.AiAnalysisDocument;
import com.apachecamel.mini_integration_platform.model.document.ExceptionDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * NotificationService
 *
 * Sends an email notification via JavaMailSender (Spring Boot Mail / SMTP)
 * when an AI exception analysis is ready.
 *
 * Uses spring-boot-starter-mail — no external SDK, works with Gmail,
 * Outlook, SendGrid SMTP relay, or any SMTP provider.
 *
 * Called by ExceptionAnalysisService on a wireTap thread — the main
 * consumer thread is never blocked waiting for email delivery.
 *
 * Email structure:
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Subject: [SEVERITY] Exception Alert — Route | Scenario | Code      │
 * │                                                                      │
 * │  ── Exception Details ───────────────────────────────────────────── │
 * │  Original Message ID : ID:abc-123                                    │
 * │  Scenario            : Scenario1 (WW)                                │
 * │  Failed Route Leg    : Route2                                        │
 * │  Exception Code      : ExceptionCode3                                │
 * │  Timestamp           : 2026-03-13T10:00:00Z                         │
 * │  Payload             : {"type":"ERROR"}                              │
 * │                                                                      │
 * │  ── AI Analysis ─────────────────────────────────────────────────── │
 * │  Severity            : LOW                                           │
 * │  Summary             : The message was rejected because...           │
 * │  Root Cause          : MessageValidatorProcessor throws...           │
 * │  Suggested Action    : Review upstream producer...                   │
 * └──────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final JavaMailSender javaMailSender;

  @Value("${notification.from.email}")
  private String fromEmail;

  @Value("${notification.from.name:Mini Integration Platform}")
  private String fromName;

  @Value("${notification.to.email}")
  private String toEmail;

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Sends an email notification with the AI analysis of an exception.
   *
   * Called from ExceptionAnalysisService on a wireTap thread.
   * Failures are logged but never rethrown — a notification failure
   * must never affect the exception persistence or routing flow.
   *
   * @param document  the saved ExceptionDocument from MongoDB
   * @param analysis  the AI analysis result from the Anthropic API
   */
  public void sendExceptionAlert(ExceptionDocument document, AiAnalysisDocument analysis) {
    try {
      String subject = buildSubject(document, analysis);
      String body    = buildEmailBody(document, analysis);

      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(fromName + " <" + fromEmail + ">");
      message.setTo(toEmail);
      message.setSubject(subject);
      message.setText(body);

      javaMailSender.send(message);

      log.info("[NotificationService] Email sent — to='{}' subject='{}'",
              toEmail, subject);

    } catch (Exception e) {
      log.error("[NotificationService] Failed to send email notification — {}",
              e.getMessage(), e);
      // Do NOT rethrow — notification failure must never propagate
    }
  }

  // ── Subject Builder ────────────────────────────────────────────────────────

  private String buildSubject(ExceptionDocument doc, AiAnalysisDocument analysis) {
    String severity = analysis.getSeverity() != null ? analysis.getSeverity() : "UNKNOWN";
    String route    = doc.getRouteInfo()   != null ? doc.getRouteInfo().getRouteName()      : "Unknown";
    String scenario = doc.getRoutingSlip() != null ? doc.getRoutingSlip().getScenarioName() : "Unknown";
    String code     = doc.getExceptionCode() != null ? doc.getExceptionCode() : "Unknown";

    return String.format("[%s] Exception Alert — %s | %s | %s",
            severity, route, scenario, code);
  }

  // ── Body Builder ───────────────────────────────────────────────────────────

  private String buildEmailBody(ExceptionDocument doc, AiAnalysisDocument analysis) {
    String routeName = doc.getRouteInfo()   != null ? doc.getRouteInfo().getRouteName()      : "N/A";
    String routeSrc  = doc.getRouteInfo()   != null ? doc.getRouteInfo().getSource()         : "N/A";
    String routeTgt  = doc.getRouteInfo()   != null ? doc.getRouteInfo().getTarget()         : "N/A";
    String scenario  = doc.getRoutingSlip() != null ? doc.getRoutingSlip().getScenarioName() : "N/A";
    String country   = doc.getRoutingSlip() != null ? doc.getRoutingSlip().getCountryCode()  : "N/A";

    return String.format(
            "══════════════════════════════════════════════════════%n" +
                    "  EXCEPTION ALERT — Mini Integration Platform%n" +
                    "══════════════════════════════════════════════════════%n" +
                    "%n" +
                    "── Exception Details ────────────────────────────────%n" +
                    "Original Message ID  : %s%n" +
                    "Scenario             : %s (%s)%n" +
                    "Failed Route Leg     : %s%n" +
                    "Route Source         : %s%n" +
                    "Route Target         : %s%n" +
                    "Exception Code       : %s%n" +
                    "Timestamp            : %s%n" +
                    "Payload              : %s%n" +
                    "%n" +
                    "── AI Analysis ──────────────────────────────────────%n" +
                    "Severity             : %s%n" +
                    "Summary              : %s%n" +
                    "Root Cause           : %s%n" +
                    "Suggested Action     : %s%n" +
                    "Analysed At          : %s%n" +
                    "%n" +
                    "══════════════════════════════════════════════════════%n" +
                    "This alert was generated automatically by the%n" +
                    "Mini Integration Platform AI Exception Analysis.%n" +
                    "══════════════════════════════════════════════════════",
            doc.getOriginalMessageId(),
            scenario, country,
            routeName,
            routeSrc,
            routeTgt,
            doc.getExceptionCode(),
            doc.getEventTimestamp(),
            doc.getPayload(),
            analysis.getSeverity(),
            analysis.getSummary(),
            analysis.getRootCause(),
            analysis.getSuggestedAction(),
            analysis.getAnalysedAt()
    );
  }
}