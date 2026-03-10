package com.apachecamel.mini_integration_platform.routes;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AuditRoute
 *
 * Consumes audit messages from COMMON.AUDIT.SERVICE.IN.
 * Default value on @Value ensures the bean constructs safely even if
 * properties are resolved late.
 *
 * Task 6: replace the process() body with a persistence call.
 */
@Slf4j
@Component
public class AuditRoute extends RouteBuilder {

    @Value("${app.queue.audit:COMMON.AUDIT.SERVICE.IN}")
    private String auditQueue;

    @Override
    public void configure() {
        from("activemq:" + auditQueue)
                .routeId("audit-consumer-route")
                .log("AUDIT RECEIVED: ${body}")
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    log.info("[AuditRoute] Audit message received:\n{}", body);
                    // Task 6 extension point: .bean(AuditPersistenceService.class, "save")
                });
    }
}