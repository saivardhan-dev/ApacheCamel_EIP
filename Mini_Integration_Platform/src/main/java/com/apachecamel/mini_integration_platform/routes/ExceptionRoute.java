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
 * Consumes exception messages from COMMON.EXCEPTION.SERVICE.IN.
 *
 * Flow:
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  COMMON.EXCEPTION.SERVICE.IN                                         │
 * │       ↓                                                              │
 * │  ExceptionPersistenceService.save()                                  │
 * │       ↓ returns saved ExceptionDocument (with MongoDB _id)           │
 * │  wireTap fires on separate thread — main thread released             │
 * │       ↓                                                              │
 * │  ExceptionAnalysisAgent.analyse(document)                          │
 * │       ↓                                                              │
 * │  Anthropic API call → structured analysis JSON                       │
 * │       ↓                                                              │
 * │  MongoDB "exceptions" document updated with aiAnalysis field         │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * The AI analysis runs on a separate wireTap thread so the consumer thread
 * is released immediately after persistence — a slow or unavailable
 * Anthropic API never blocks the exception consumer.
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

                // ── Step 1: Parse JSON and save to MongoDB — synchronous ───────────
                // Returns the saved document with its MongoDB _id so the AI service
                // can update the same document after analysis.
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    ExceptionDocument saved = exceptionPersistenceService.save(body);

                    // Pass the saved document as an exchange property so wireTap
                    // onPrepare can read it on the separate thread
                    exchange.setProperty("savedExceptionDocument", saved);
                })

                .log("EXCEPTION persisted → triggering AI analysis (async)")

                // ── Step 2: AI Analysis — async via wireTap ───────────────────────
                // "log:ai-analysis" is a no-op endpoint — wireTap just needs a valid
                // URI. The real work happens in onPrepare on a separate thread.
                // If Anthropic API is slow (up to 30s), the consumer thread is
                // completely unaffected — it is already released.
                .wireTap("log:ai-analysis")
                .onPrepare(ex -> {
                    ExceptionDocument doc = ex.getProperty(
                            "savedExceptionDocument", ExceptionDocument.class);
                    if (doc != null) {
                        exceptionAnalysisAgent.analyse(doc);
                    } else {
                        log.warn("[ExceptionRoute] savedExceptionDocument is null — skipping AI analysis");
                    }
                })
                .end()

                .log("EXCEPTION consumer done — AI analysis running in background");
    }
}