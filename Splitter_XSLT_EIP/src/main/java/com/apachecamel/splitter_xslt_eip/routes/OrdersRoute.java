package com.apachecamel.splitter_xslt_eip.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class OrdersRoute extends RouteBuilder {

    @Override
    public void configure() {

        from("activemq:queue:orders.producer")
                .routeId("entry-route")
                .log("Entry Route - Received message from orders.producer: ${body}")
                .to("activemq:queue:CORE.SPLITTER.SERVICE.IN");

        from("activemq:queue:CORE.SPLITTER.SERVICE.IN")
                .routeId("xml-splitter-route")
                .log("Received batch XML message")
                .split(xpath("/orders/order"))
                .log("Split order: ${body}")
                .to("activemq:queue:CORE.TRANS.SERVICE.IN")
                .end();

        from("activemq:queue:CORE.TRANS.SERVICE.IN")
                .routeId("transformation-route")
                .log("Transformation Route - Received XML: ${body}")
                .log("Applying XSLT transformation")
                .to("xslt:classpath:xslt/orderXmlToJson.xsl")
                .log("Transformation Route - JSON Output: ${body}")
                .setBody(simple("${body} , \"Enhanced message\""))
                .log("Enhanced Message Before Exit: ${body}")
                .to("direct:exitRoute");

        from("direct:exitRoute")
                .routeId("exit-route")
                .log("Exit Route - Sending transformed message to orders.consumer")
                .to("activemq:queue:orders.consumer");
    }
}
