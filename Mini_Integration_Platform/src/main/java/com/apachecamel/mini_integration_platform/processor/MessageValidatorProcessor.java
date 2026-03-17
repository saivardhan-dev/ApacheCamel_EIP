package com.apachecamel.mini_integration_platform.processor;

import com.fasterxml.jackson.core.JsonParseException;
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
 * Validates the incoming message in CoreProcessingRoute (Route-2).
 *
 * Exception mapping:
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  Failure Scenario                    Exception            Code          │
 * │  ────────────────────────────────────────────────────────────────────   │
 * │  Body is null or blank               RuntimeException     ExceptionCode4│
 * │  Body is not valid JSON              JsonParseException   ExceptionCode1│
 * │  type field missing / blank / null   JsonParseException   ExceptionCode1│
 * │  type is not a string node           JsonParseException   ExceptionCode1│
 * │  type = "ERROR" (deliberate)         RuntimeException     ExceptionCode4│
 * │  amount field missing                NumberFormatException ExceptionCode2│
 * │  amount = 0                          NumberFormatException ExceptionCode2│
 * │  amount is negative                  NumberFormatException ExceptionCode2│
 * │  amount is non-numeric string        NumberFormatException ExceptionCode2│
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Test messages:
 *   Code1 : <message>xml</message>
 *   Code1 : { "type": "Cars", "amount": abc }        ← unquoted, invalid JSON
 *   Code1 : { "data": "no type field" }              ← missing type
 *   Code1 : { "type": "",    "amount": 100 }         ← blank type
 *   Code1 : { "type": 12345, "amount": 100 }         ← type is a number not string
 *   Code2 : { "type": "Cars" }                       ← amount missing
 *   Code2 : { "type": "Cars", "amount": 0 }          ← zero amount
 *   Code2 : { "type": "Cars", "amount": -50 }        ← negative amount
 *   Code2 : { "type": "Cars", "amount": "abc" }      ← non-numeric string
 *   Code4 : { "type": "ERROR", "amount": 100 }       ← deliberate rejection
 *   Happy : { "type": "Cars",        "amount": 2000 }
 *   Happy : { "type": "Electronics", "amount": 500  }
 *   Happy : { "type": "Furniture",   "amount": 800  }
 *   Happy : { "type": "Anything",    "amount": 1500 }
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
        // Throws: RuntimeException → ExceptionCode4
        if (body == null || body.isBlank()) {
            throw new RuntimeException(
                    "Message body is null or empty — cannot process"
            );
        }

        // ── Check 2: must be valid JSON ───────────────────────────────────────
        // Throws: JsonParseException → ExceptionCode1
        // XML, malformed JSON, unquoted values (amount: abc) all fail here.
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new JsonParseException(
                    null,
                    "Invalid JSON in message body — " + e.getMessage()
            );
        }

        // ── Check 3: type field must exist, be a string node, and be non-blank ─
        // Throws: JsonParseException → ExceptionCode1
        //
        // Three sub-cases all treated as malformed message structure:
        //   - "type" key missing entirely
        //   - "type": ""  (blank string)
        //   - "type": 123 (not a text node — number, boolean, object, array)
        if (!node.has("type")
                || !node.get("type").isTextual()
                || node.get("type").asText().isBlank()) {
            throw new JsonParseException(
                    null,
                    "Message JSON 'type' field is missing, blank, or not a string"
            );
        }

        String type = node.get("type").asText().trim();

        // ── Check 4: type = "ERROR" → deliberate rejection ────────────────────
        // Throws: RuntimeException → ExceptionCode4
        if ("ERROR".equalsIgnoreCase(type)) {
            throw new RuntimeException(
                    "Deliberate rejection — message type is ERROR"
            );
        }

        // ── Check 5: amount field must be present ─────────────────────────────
        // Throws: NumberFormatException → ExceptionCode2
        if (!node.has("amount") || node.get("amount").isNull()) {
            throw new NumberFormatException(
                    "Message JSON is missing required field: 'amount'"
            );
        }

        // ── Check 6: amount must be numeric, positive, and non-zero ──────────
        // Case A: "amount": abc   (unquoted) → invalid JSON → fails at Check 2
        //                                      → ExceptionCode1
        // Case B: "amount": "abc" (quoted)   → valid JSON string, not numeric
        //                                      → ExceptionCode2
        // Case C: "amount": 0                → zero is not a valid amount
        //                                      → ExceptionCode2
        // Case D: "amount": -50              → negative is not a valid amount
        //                                      → ExceptionCode2
        // Case E: "amount": 1500             → valid number, passes
        JsonNode amountNode = node.get("amount");
        double amount;

        if (amountNode.isNumber()) {
            amount = amountNode.asDouble();
        } else {
            // Quoted string — try to parse
            String amountStr = amountNode.asText();
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                throw new NumberFormatException(
                        "Invalid amount value '" + amountStr + "' — must be numeric"
                );
            }
        }

        // Zero or negative amount check
        // Throws: NumberFormatException → ExceptionCode2
        if (amount <= 0) {
            throw new NumberFormatException(
                    "Invalid amount value '" + amount + "' — must be greater than zero"
            );
        }

        log.info("[MessageValidatorProcessor] Validated — type='{}' amount='{}'",
                type, amount);
    }
}