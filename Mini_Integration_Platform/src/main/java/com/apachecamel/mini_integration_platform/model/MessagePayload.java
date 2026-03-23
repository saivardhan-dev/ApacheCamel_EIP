package com.apachecamel.mini_integration_platform.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MessagePayload
 *
 * Generic dynamic message container. Accepts ANY JSON payload regardless
 * of field names or structure. No hardcoded fields — all values stored
 * in a Map<String, Object>.
 *
 * Deserialisation (JSON → Java):
 *   Jackson calls @JsonAnySetter for every key-value pair in the JSON.
 *   Any field name, any value type — all stored in the fields map.
 *
 *   { "type": "Cars", "amount": 14000 }
 *     → fields = { "type" → "Cars", "amount" → 14000.0 }
 *
 *   { "AppID": "ID-001", "messages": [...] }
 *     → fields = { "AppID" → "ID-001", "messages" → List }
 *
 * Serialisation (Java → JSON):
 *   @JsonAnyGetter exposes all map entries as top-level JSON keys.
 */
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessagePayload {

    /**
     * Generic container for all message fields.
     * Not final — Jackson needs to be able to work with it freely.
     */
    private Map<String, Object> fields = new HashMap<>();

    // ── Deserialisation — @JsonAnySetter ──────────────────────────────────────

    /**
     * Called by Jackson for every key-value pair in the JSON body.
     * Any field — string, number, array, object — lands here.
     */
    @JsonAnySetter
    public void put(String key, Object value) {
        fields.put(key, value);
        log.debug("[MessagePayload] Field deserialised — key='{}' type='{}'",
                key, value != null ? value.getClass().getSimpleName() : "null");
    }

    // ── Serialisation — @JsonAnyGetter ────────────────────────────────────────

    /**
     * Called by Jackson when serialising back to JSON.
     * @JsonIgnore prevents Jackson from trying to find a setter for this.
     */
    @JsonAnyGetter
    @JsonIgnore
    public Map<String, Object> getFields() {
        return fields;
    }

    // ── Field access helpers ──────────────────────────────────────────────────

    public boolean has(String fieldName) {
        return fields.containsKey(fieldName) && fields.get(fieldName) != null;
    }

    public Object get(String fieldName) {
        return fields.get(fieldName);
    }

    public String getString(String fieldName) {
        Object value = fields.get(fieldName);
        return value != null ? value.toString() : null;
    }

    public Double getDouble(String fieldName) {
        Object value = fields.get(fieldName);
        if (value == null)           return null;
        if (value instanceof Double)  return (Double) value;
        if (value instanceof Integer) return ((Integer) value).doubleValue();
        if (value instanceof Long)    return ((Long) value).doubleValue();
        if (value instanceof Float)   return ((Float) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            throw new NumberFormatException(
                    "Field '" + fieldName + "' value '" + value + "' is not numeric"
            );
        }
    }

    public Set<String> fieldNames() {
        return fields.keySet();
    }

    // ── Envelope helpers ──────────────────────────────────────────────────────

    /**
     * Returns true if this payload is a multi-message envelope.
     * Detected by the presence of a "messages" key containing a List.
     */
    @JsonIgnore
    public boolean isEnvelope() {
        return fields.get("messages") instanceof List;
    }

    /**
     * Returns the AppID from the envelope header if present.
     */
    @JsonIgnore
    public String getAppId() {
        Object appId = fields.get("AppID");
        return appId != null ? appId.toString() : null;
    }

    /**
     * Returns the messages list from the envelope.
     * Each entry is a Map<String, Object> representing one message.
     * Returns null if this is not an envelope payload.
     */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMessages() {
        Object messages = fields.get("messages");
        if (messages instanceof List) {
            return (List<Map<String, Object>>) messages;
        }
        return null;
    }

    @Override
    public String toString() {
        return "MessagePayload" + fields.toString();
    }
}