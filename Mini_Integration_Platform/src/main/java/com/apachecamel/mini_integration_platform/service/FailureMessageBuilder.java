package com.apachecamel.mini_integration_platform.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import static com.apachecamel.mini_integration_platform.processor.ScenarioProcessor.ORIGINAL_MESSAGE_ID;
import static com.apachecamel.mini_integration_platform.processor.ScenarioProcessor.CURRENT_ROUTE_NAME;

/**
 * FailureMessageBuilder
 *
 * Returns only the original message payload for the failure queue.
 * No headers, no metadata — just the raw body exactly as received.
 *
 * COMMON.FAILURE.SERVICE.IN will contain messages like:
 *
 *   { "type": "Cars", "amount": 2000 }
 *
 * This makes it easy to inspect, fix, and replay the message
 * directly back to the entry queue without any unwrapping.
 */
@Slf4j
@Component
public class FailureMessageBuilder {

  /**
   * Returns the original message body from the exchange.
   *
   * @param exchange  the exchange in the onException handler
   * @return          the raw original payload string
   */
  public String build(Exchange exchange) {
    String payload = exchange.getIn().getBody(String.class);

    log.info("[FailureMessageBuilder] Sending original payload to failure queue — " +
                    "msgId='{}' route='{}'",
            exchange.getIn().getHeader(ORIGINAL_MESSAGE_ID,  String.class),
            exchange.getIn().getHeader(CURRENT_ROUTE_NAME,   String.class));

    return payload;
  }
}