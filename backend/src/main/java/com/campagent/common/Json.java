package com.campagent.common;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() {}
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("JSON serialization failed", e); }
    }
    public static Map<String,Object> map(String text) {
        try { return MAPPER.readValue(text, new TypeReference<Map<String,Object>>(){}); }
        catch (Exception e) { throw new IllegalArgumentException("无效 JSON"); }
    }
    public static List<Map<String,Object>> rows(String text) {
        try { return MAPPER.readValue(text, new TypeReference<List<Map<String,Object>>>(){}); }
        catch (Exception e) { throw new IllegalArgumentException("无效 JSON 数组"); }
    }
}

