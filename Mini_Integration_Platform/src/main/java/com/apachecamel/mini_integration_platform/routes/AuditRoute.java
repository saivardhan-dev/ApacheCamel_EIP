package com.apachecamel.mini_integration_platform.routes;

import com.apachecamel.mini_integration_platform.service.persistence.AuditPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AuditRoute  (Task 6 — updated)
 *
 * Consumes audit messages from COMMON.AUDIT.SERVICE.IN and persists
 * each one to the MongoDB "audits" collection via AuditPersistenceService.
 *
 * Flow:
 *   COMMON.AUDIT.SERVICE.IN
 *       ↓
 *   Read body (Audit JSON string)
 *       ↓
 *   AuditPersistenceService.save()
 *       ↓
 *   MongoDB → "audits" collection → new AuditDocument stored
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRoute extends RouteBuilder {

    private final AuditPersistenceService auditPersistenceService;

    @Value("${app.queue.audit:COMMON.AUDIT.SERVICE.IN}")
    private String auditQueue;

    @Override
    public void configure() {
        log.info("[AuditRoute] Consumer started on queue='{}'", auditQueue);

        from("activemq:" + auditQueue)
                .routeId("audit-consumer-route")
                .log("AUDIT RECEIVED → persisting to MongoDB")

                // ── Task 6: Parse JSON and save to MongoDB "audits" collection ─────
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    auditPersistenceService.save(body);
                })

                .log("AUDIT PERSISTED → MongoDB audits collection");
    }
}