package com.apachecamel.mini_integration_platform.routes;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ExceptionRoute
 *
 * Consumes exception messages from COMMON.EXCEPTION.SERVICE.IN.
 * Default value on @Value ensures the bean constructs safely even if
 * properties are resolved late.
 *
 * Task 6: replace the process() body with a persistence call.
 */
@Slf4j
@Component
public class ExceptionRoute extends RouteBuilder {

    @Value("${app.queue.exception:COMMON.EXCEPTION.SERVICE.IN}")
    private String exceptionQueue;

    @Override
    public void configure() {
        from("activemq:" + exceptionQueue)
                .routeId("exception-consumer-route")
                .log("EXCEPTION RECEIVED: ${body}")
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    log.error("[ExceptionRoute] Exception message received:\n{}", body);
                    // Task 6 extension point: .bean(ExceptionPersistenceService.class, "save")
                });
    }
}