package com.apachecamel.mini_integration_platform.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

/**
 * MessageValidatorProcessor
 *
 * Plugged into Route-2 (CoreProcessingRoute) to validate the incoming message body.
 *
 * Throws deliberately to trigger the ExceptionProcessor in these cases:
 *
 *   1. Body is null or blank           → throws IllegalArgumentException
 *   2. Body is not valid JSON          → throws JsonParsingException (mapped → ExceptionCode1)
 *   3. JSON missing required "type" field → throws IllegalArgumentException (mapped → ExceptionCode3)
 *   4. "type" field value is "ERROR"   → throws RuntimeException (mapped → ExceptionCode3)
 *
 * To test happy path:  send  {"type":"ORDER","data":"anything"}
 * To test exception:   send  {"type":"ERROR","data":"anything"}
 *                      send  not-valid-json
 *                      send  {"data":"no type field here"}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageValidatorProcessor implements Processor {

    private final ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {

        String body = exchange.getIn().getBody(String.class);

        // ── Check 1: null or blank body ───────────────────────────────────────
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "Message body is null or empty — cannot process"
            );
        }

        // ── Check 2: must be valid JSON ───────────────────────────────────────
        // Jackson will throw JsonParseException if invalid → maps to ExceptionCode1
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            // Wrap so the class name is clear in the exception code lookup
            throw new com.fasterxml.jackson.core.JsonParseException(null,
                    "Invalid JSON in message body: " + e.getMessage());
        }

        // ── Check 3: must contain a "type" field ──────────────────────────────
        if (!node.has("type") || node.get("type").asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Message JSON is missing required field: 'type'"
            );
        }

        // ── Check 4: "type" = "ERROR" triggers a deliberate exception ─────────
        String type = node.get("type").asText();
        if ("ERROR".equalsIgnoreCase(type)) {
            throw new RuntimeException(
                    "Deliberate exception triggered — message type is ERROR. " +
                            "This routes to COMMON.EXCEPTION.SERVICE.IN"
            );
        }

        log.info("[MessageValidatorProcessor] Message validated successfully — type='{}'", type);
    }
}
