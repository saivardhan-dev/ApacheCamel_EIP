package com.apachecamel.ehcache_cbr_eip.routes;


import com.apachecamel.ehcache_cbr_eip.exception.InvalidDestinationException;
import com.apachecamel.ehcache_cbr_eip.processor.DynamicRoutingProcessor;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class DynamicRouterRoute extends RouteBuilder {

    private final DynamicRoutingProcessor processor;

    public DynamicRouterRoute(DynamicRoutingProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {

        onException(InvalidDestinationException.class)
                .handled(true)
                .log("${exception.message}");

        from("activemq:queue:orders.producer")
                .routeId("dynamic-router-route")

                .log("Received message: ${body}")

                .process(processor)

                .log("Routing to queue: ${exchangeProperty.destinationQueue}")

                .toD("activemq:queue:${exchangeProperty.destinationQueue}");
    }
}
