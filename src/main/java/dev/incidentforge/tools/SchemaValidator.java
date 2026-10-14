package dev.incidentforge.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * JSON Schema validation for tool arguments and model outputs, using the same validator
 * implementation the MCP SDK uses server-side.
 */
@Component
public class SchemaValidator {

    public record Result(boolean valid, String error) {
    }

    private final ObjectMapper objectMapper;
    private final JsonSchemaValidator validator;
    private final Map<String, Map<String, Object>> modelSchemas = new ConcurrentHashMap<>();

    public SchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.validator = new DefaultJsonSchemaValidator(objectMapper);
    }

    public JsonSchemaValidator delegate() {
        return validator;
    }

    public Result validate(Map<String, Object> schema, Object instance) {
        JsonSchemaValidator.ValidationResponse response = validator.validate(schema, instance);
        return new Result(response.valid(), response.valid() ? null : response.errorMessage());
    }

    /** Validates against a model-output schema under {@code classpath:schemas/<name>.json}. */
    public Result validateModelOutput(String schemaName, Object instance) {
        return validate(modelSchema(schemaName), instance);
    }

    public Map<String, Object> modelSchema(String schemaName) {
        return modelSchemas.computeIfAbsent(schemaName, name -> {
            try (InputStream in = new ClassPathResource("schemas/" + name + ".json").getInputStream()) {
                return objectMapper.readValue(in, new TypeReference<Map<String, Object>>() {
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Missing schema " + name, e);
            }
        });
    }
}
