package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.service.persistence.ExceptionPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ExceptionRoute  (Task 6 — updated)
 *
 * Consumes exception messages from COMMON.EXCEPTION.SERVICE.IN and persists
 * each one to the MongoDB "exceptions" collection via ExceptionPersistenceService.
 *
 * Flow:
 *   COMMON.EXCEPTION.SERVICE.IN
 *       ↓
 *   Read body (Exception JSON string)
 *       ↓
 *   ExceptionPersistenceService.save()
 *       ↓
 *   MongoDB → "exceptions" collection → new ExceptionDocument stored
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExceptionRoute extends RouteBuilder {

    private final ExceptionPersistenceService exceptionPersistenceService;

    @Value("${app.queue.exception:COMMON.EXCEPTION.SERVICE.IN}")
    private String exceptionQueue;

    @Override
    public void configure() {
        log.info("[ExceptionRoute] Consumer started on queue='{}'", exceptionQueue);

        from("activemq:" + exceptionQueue)
                .routeId("exception-consumer-route")
                .log("EXCEPTION RECEIVED → persisting to MongoDB")

                // ── Task 6: Parse JSON and save to MongoDB "exceptions" collection ─
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    exceptionPersistenceService.save(body);
                })

                .log("EXCEPTION PERSISTED → MongoDB exceptions collection");
    }
}