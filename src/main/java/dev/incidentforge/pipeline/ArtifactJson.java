package dev.incidentforge.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ArtifactJson {

    private final ObjectMapper objectMapper;

    public ArtifactJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not read " + type.getSimpleName(), e);
        }
    }

    public <T> T convert(JsonNode node, Class<T> type) {
        return objectMapper.convertValue(node, type);
    }

    public JsonNode tree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
