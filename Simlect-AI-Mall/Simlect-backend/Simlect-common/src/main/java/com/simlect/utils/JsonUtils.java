package com.simlect.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared Jackson helpers for Redis/MQ payloads (replaces Fastjson).
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialize failed", e);
        }
    }

    public static <T> T parseObject(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse failed", e);
        }
    }

    public static <T> T parseObject(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse failed", e);
        }
    }

    public static <T> java.util.List<T> parseArray(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, elementType));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse array failed", e);
        }
    }

    /**
     * Parse arbitrary JSON into Map / List / scalar (similar to Fastjson JSON.parse).
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse failed", e);
        }
    }

    public static JsonNode parseTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse tree failed", e);
        }
    }

    public static ObjectNode createObjectNode() {
        return MAPPER.createObjectNode();
    }
}
