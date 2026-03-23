package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.model.document.ExceptionDocument;
import com.apachecamel.mini_integration_platform.service.ai.ExceptionAnalysisAgent;
import com.apachecamel.mini_integration_platform.service.persistence.ExceptionPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ExceptionRoute
 *
 * Consumes failed messages from COMMON.EXCEPTION.SERVICE.IN.
 *
 * Flow:
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  COMMON.EXCEPTION.SERVICE.IN                                         │
 * │       ↓                                                              │
 * │  Step 1: ExceptionPersistenceService.save()                          │
 * │          ExceptionCode already resolved by ExceptionJsonBuilder      │
 * │          and embedded in the JSON body — read directly from JSON.    │
 * │          → persist ExceptionDocument to MongoDB "exceptions"         │
 * │       ↓                                                              │
 * │  Step 2: wireTap → ExceptionAnalysisAgent.analyse() — async         │
 * │          → checkAuditHistory    reads EXIT audits from MongoDB       │
 * │          → checkExceptionDetail reads exception from MongoDB         │
 * │          → AI produces structured analysis                           │
 * │          → MongoDB exception document updated with aiAnalysis        │
 * │          → NotificationService sends email via Gmail SMTP            │
 * └──────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExceptionRoute extends RouteBuilder {

    private final ExceptionPersistenceService exceptionPersistenceService;
    private final ExceptionAnalysisAgent      exceptionAnalysisAgent;

    @Value("${app.queue.exception:COMMON.EXCEPTION.SERVICE.IN}")
    private String exceptionQueue;

    @Override
    public void configure() {
        log.info("[ExceptionRoute] Consumer started on queue='{}'", exceptionQueue);

        from("activemq:" + exceptionQueue)
                .routeId("exception-consumer-route")
                .log("EXCEPTION received → persisting to MongoDB")

                // ── Step 1: Persist to MongoDB ────────────────────────────────────
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    ExceptionDocument saved = exceptionPersistenceService.save(body);

                    if (saved == null) {
                        log.error("[ExceptionRoute] ExceptionPersistenceService returned null " +
                                "— body may be null or malformed. Body='{}'", body);
                        return;
                    }

                    exchange.setProperty("savedExceptionDocument", saved);

                    log.info("[ExceptionRoute] Persisted — id='{}' msgId='{}' code='{}'",
                            saved.getId(),
                            saved.getOriginalMessageId(),
                            saved.getExceptionCode());
                })

                .log("EXCEPTION persisted → triggering AI analysis from MongoDB (async)")

                // ── Step 2: AI Analysis — async wireTap ───────────────────────────
                // Fires on separate thread — consumer thread released immediately.
                // Agent queries MongoDB using originalMessageId:
                //   checkAuditHistory    → "audits"     collection (EXIT records only)
                //   checkExceptionDetail → "exceptions" collection
                .wireTap("log:ai-analysis")
                .onPrepare(ex -> {
                    ExceptionDocument saved = ex.getProperty(
                            "savedExceptionDocument", ExceptionDocument.class);

                    if (saved == null) {
                        log.warn("[ExceptionRoute] savedExceptionDocument is null " +
                                "— skipping AI analysis");
                        return;
                    }

                    log.info("[ExceptionRoute] AI analysis starting — msgId='{}'",
                            saved.getOriginalMessageId());

                    exceptionAnalysisAgent.analyse(saved);
                })
                .end()

                .log("EXCEPTION consumer done — AI analysis running in background");
    }
}