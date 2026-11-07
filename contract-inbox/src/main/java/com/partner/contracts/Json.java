package com.partner.contracts;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    static byte[] bytes(Object value) {
        try { return MAPPER.writeValueAsBytes(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid document", e); }
    }
    static String text(Object value) { return new String(bytes(value), StandardCharsets.UTF_8); }
    static Map<String, Object> parse(String value) {
        try { return MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid document", e); }
    }
    static Map<String, Object> object(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    static String str(Map<String, ?> value, String key) { return (String) value.get(key); }
    static int integer(Map<String, ?> value, String key) { return ((Number) value.get(key)).intValue(); }
}
