package com.apachecamel.mini_integration_platform.processor;

import com.apachecamel.mini_integration_platform.model.MessagePayload;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

/**
 * MessageValidatorProcessor
 *
 * Deserialises the incoming JSON body into a generic MessagePayload map.
 * No field validation — accepts any payload regardless of what fields
 * are present. The CBR rules in Scenarios.json decide what to do with
 * whatever fields arrive.
 *
 * Responsibilities:
 *   1. Check body is not null or blank
 *   2. Deserialise JSON → MessagePayload (generic map)
 *   3. Store on exchange for reuse by DynamicQueueResolver
 *
 * The only failures here are structural:
 *   Null or blank body    → RuntimeException     → ExceptionCode4
 *   Invalid JSON          → JsonParseException   → ExceptionCode1
 *
 * Everything else — missing fields, wrong types, unknown fields —
 * is handled by DynamicQueueResolver based on Scenarios.json rules.
 * If a rule references a field that is absent, the rule is skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageValidatorProcessor implements Processor {

    private final ObjectMapper objectMapper;

    /** Exchange property key where the deserialised payload is stored */
    public static final String PROP_MESSAGE_PAYLOAD = "messagePayload";

    @Override
    public void process(Exchange exchange) throws Exception {

        String body = exchange.getIn().getBody(String.class);

        // ── Check 1: null or blank body ───────────────────────────────────────
        // The only hard structural requirement — something must be there
        if (body == null || body.isBlank()) {
            throw new RuntimeException(
                    "Message body is null or empty — cannot process"
            );
        }

        // ── Check 2: must be valid JSON ───────────────────────────────────────
        // XML, malformed JSON, plain text all fail here → ExceptionCode1
        // Any valid JSON — regardless of field names — passes
        MessagePayload payload;
        try {
            payload = objectMapper.readValue(body, MessagePayload.class);
        } catch (Exception e) {
            throw new JsonParseException(
                    null,
                    "Failed to deserialise message body — " + e.getMessage()
            );
        }

        // ── Store on exchange ─────────────────────────────────────────────────
        // DynamicQueueResolver reads this in the next step.
        // No field validation here — rules in Scenarios.json drive everything.
        exchange.setProperty(PROP_MESSAGE_PAYLOAD, payload);

        log.info("[MessageValidatorProcessor] Deserialised — fields={}",
                payload.fieldNames());
    }
}