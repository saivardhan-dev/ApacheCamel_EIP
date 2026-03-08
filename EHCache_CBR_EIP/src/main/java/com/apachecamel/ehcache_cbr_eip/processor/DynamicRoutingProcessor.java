package com.apachecamel.ehcache_cbr_eip.processor;

import com.apachecamel.ehcache_cbr_eip.exception.InvalidDestinationException;
import com.apachecamel.ehcache_cbr_eip.service.DestinationCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

@Component
public class DynamicRoutingProcessor implements Processor {

    private final DestinationCacheService cacheService;

    public DynamicRoutingProcessor(DestinationCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {

        String body = exchange.getIn().getBody(String.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(body);

        String type = jsonNode.get("type").asText();

        String destination = cacheService.getDestination(type);

        System.out.println("Cache Lookup for type: " + type);
        System.out.println("Destination Queue: " + destination);

        if (destination == null) {
            throw new InvalidDestinationException(type);
        }

        exchange.setProperty("destinationQueue", destination);
    }

}
