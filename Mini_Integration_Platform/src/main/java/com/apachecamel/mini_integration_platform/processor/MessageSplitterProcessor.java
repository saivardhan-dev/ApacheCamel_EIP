package com.apachecamel.mini_integration_platform.processor;

import com.apachecamel.mini_integration_platform.model.MessagePayload;
import com.apachecamel.mini_integration_platform.model.Scenario;
import com.apachecamel.mini_integration_platform.model.SplitterConfig;
import com.apachecamel.mini_integration_platform.service.ScenarioCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MessageSplitterProcessor
 *
 * Runs as Step 0 in ScenarioEntryRoute — before ScenarioProcessor.
 * Detects multi-message envelope and splits into individual messages.
 *
 * Field names are fully config-driven from Scenarios.json splitter block.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageSplitterProcessor implements Processor {

    private final ObjectMapper         objectMapper;
    private final ScenarioCacheService scenarioCacheService;

    public static final String HEADER_APP_ID         = "AppID";
    /** JMS message header storing the original full envelope body before any unwrapping.
     * Stored as a HEADER (not exchange property) so it survives the JMS queue hop
     * from ScenarioEntryRoute (InOnly) to CoreProcessingRoute. */
    public static final String PROP_ORIGINAL_BODY    = "OriginalEnvelopeBody";
    public static final String PROP_PENDING_MESSAGES = "pendingMessages";
    public static final String PROP_IS_SPLIT         = "isSplitMessage";
    public static final String PROP_BATCH_SIZE       = "batchSize";
    public static final String PROP_BATCH_INDEX      = "batchIndex";

    @Override
    public void process(Exchange exchange) throws Exception {

        String body = exchange.getIn().getBody(String.class);
        if (body == null || body.isBlank()) return;

        // ── Find scenario by fromRouteId ───────────────────────────────────────
        Scenario scenario = findScenario(exchange);

        if (scenario == null) {
            log.debug("[MessageSplitterProcessor] Could not determine scenario — passthrough");
            return;
        }

        if (!scenario.hasSplitter()) {
            log.debug("[MessageSplitterProcessor] Scenario '{}' has no splitter — passthrough",
                    scenario.getScenarioName());
            return;
        }

        SplitterConfig splitter = scenario.getSplitter();

        log.debug("[MessageSplitterProcessor] Scenario '{}' has splitter — " +
                        "messagesField='{}' appIdField='{}'",
                scenario.getScenarioName(),
                splitter.getMessagesField(),
                splitter.getAppIdField());

        // ── Deserialise body ───────────────────────────────────────────────────
        MessagePayload payload;
        try {
            payload = objectMapper.readValue(body, MessagePayload.class);
        } catch (Exception e) {
            log.debug("[MessageSplitterProcessor] Body is not valid JSON — passthrough");
            return;
        }

        // ── Detect envelope mode ──────────────────────────────────────────────
        Object messagesObj = payload.get(splitter.getMessagesField());
        boolean hasAppId   = payload.has(splitter.getAppIdField());
        String  appId      = payload.getString(splitter.getAppIdField());

        if (messagesObj instanceof List) {
            // ── SPLIT mode — array of messages ────────────────────────────────
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) messagesObj;
            int batchSize = messages.size();

            if (messages.isEmpty()) {
                log.warn("[MessageSplitterProcessor] '{}' array is empty — skipping",
                        splitter.getMessagesField());
                return;
            }

            log.info("[MessageSplitterProcessor] SPLIT mode — " +
                            "scenario='{}' {}='{}' batchSize={}",
                    scenario.getScenarioName(),
                    splitter.getAppIdField(), appId, batchSize);

            // Store original envelope body as JMS HEADER so it survives the queue hop
            // from ScenarioEntryRoute (InOnly) to CoreProcessingRoute.
            exchange.getIn().setHeader(PROP_ORIGINAL_BODY, body);

            // Replace body with first message, store rest as pending
            Map<String, Object> first     = messages.get(0);
            String              firstJson = objectMapper.writeValueAsString(first);

            exchange.getIn().setBody(firstJson);
            exchange.setProperty(PROP_IS_SPLIT,        true);
            exchange.setProperty(PROP_BATCH_SIZE,       batchSize);
            exchange.setProperty(PROP_BATCH_INDEX,      1);
            exchange.setProperty(PROP_PENDING_MESSAGES, messages.subList(1, messages.size()));

            if (appId != null) {
                exchange.getIn().setHeader(HEADER_APP_ID, appId);
            }

            log.info("[MessageSplitterProcessor] Split 1/{} — body='{}' {}='{}'",
                    batchSize, firstJson, splitter.getAppIdField(), appId);

        } else if (messagesObj instanceof java.util.Map) {
            // ── UNWRAP mode — single object envelope ──────────────────────────
            @SuppressWarnings("unchecked")
            Map<String, Object> messageObj = (Map<String, Object>) messagesObj;
            String unwrappedJson = objectMapper.writeValueAsString(messageObj);

            log.info("[MessageSplitterProcessor] UNWRAP mode — " +
                            "scenario='{}' {}='{}' body='{}'",
                    scenario.getScenarioName(),
                    splitter.getAppIdField(), appId, unwrappedJson);

            // Store original envelope body as JMS HEADER so it survives the queue hop
            // from ScenarioEntryRoute (InOnly) to CoreProcessingRoute.
            exchange.getIn().setHeader(PROP_ORIGINAL_BODY, body);

            // Replace body with the unwrapped message object
            exchange.getIn().setBody(unwrappedJson);
            exchange.setProperty(PROP_IS_SPLIT, false);

            // Stamp ApplicationID as header for traceability
            if (appId != null) {
                exchange.getIn().setHeader(HEADER_APP_ID, appId);
            }

        } else {
            // messagesField not found or wrong type
            if (hasAppId) {
                // Has appIdField but no valid messagesField — misconfigured
                throw new RuntimeException(
                        "Envelope detected ('" + splitter.getAppIdField() +
                                "' field present) but messagesField '" +
                                splitter.getMessagesField() + "' not found or not an object/array. " +
                                "Check splitter.messagesField in Scenarios.json. " +
                                "Payload fields: " + payload.fieldNames()
                );
            }
            // Plain single message — passthrough
            log.debug("[MessageSplitterProcessor] Single message — no split needed");
            exchange.setProperty(PROP_IS_SPLIT, false);
        }
    }

    // ── Find scenario ──────────────────────────────────────────────────────────

    /**
     * Finds the scenario by parsing the Camel fromRouteId.
     * Route IDs are registered as "route1-{countryCode}_{scenarioName}_{instanceId}"
     * e.g. "route1-WW_Scenario2_1"
     *
     * Falls back to matching all scenarios by source queue if parsing fails.
     */
    private Scenario findScenario(Exchange exchange) {
        String fromRouteId = exchange.getFromRouteId();

        log.debug("[MessageSplitterProcessor] fromRouteId='{}'", fromRouteId);

        if (fromRouteId != null && fromRouteId.startsWith("route1-")) {
            // Parse: "route1-WW_Scenario2_1" → WW, Scenario2, 1
            String cacheKey = fromRouteId.substring("route1-".length());
            // cacheKey = "WW_Scenario2_1" — split on LAST underscore for instanceId
            // and second-to-last for scenarioName (scenarioName may contain no underscore)
            int lastUnderscore       = cacheKey.lastIndexOf('_');
            int secondLastUnderscore = cacheKey.lastIndexOf('_', lastUnderscore - 1);

            if (lastUnderscore > 0 && secondLastUnderscore >= 0) {
                String countryCode  = cacheKey.substring(0, secondLastUnderscore);
                String scenarioName = cacheKey.substring(secondLastUnderscore + 1, lastUnderscore);
                int    instanceId;
                try {
                    instanceId = Integer.parseInt(cacheKey.substring(lastUnderscore + 1));
                } catch (NumberFormatException e) {
                    instanceId = 1;
                }

                log.debug("[MessageSplitterProcessor] Parsed — countryCode='{}' " +
                                "scenarioName='{}' instanceId={}",
                        countryCode, scenarioName, instanceId);

                Scenario scenario = scenarioCacheService.getScenario(
                        countryCode, scenarioName, instanceId);
                if (scenario != null) return scenario;
            }
        }

        // Fallback — match by source queue from all scenarios
        String fromEndpointUri = exchange.getFromEndpoint() != null
                ? exchange.getFromEndpoint().getEndpointUri() : null;

        if (fromEndpointUri != null) {
            for (Scenario sc : scenarioCacheService.getAllScenarios()) {
                String sourceQueue = sc.getEffectiveSourceQueue();
                if (fromEndpointUri.contains(sourceQueue)) {
                    log.debug("[MessageSplitterProcessor] Matched scenario '{}' by source queue",
                            sc.getScenarioName());
                    return sc;
                }
            }
        }

        log.warn("[MessageSplitterProcessor] Could not find scenario for fromRouteId='{}' " +
                "fromEndpoint='{}'", fromRouteId, fromEndpointUri);
        return null;
    }
}