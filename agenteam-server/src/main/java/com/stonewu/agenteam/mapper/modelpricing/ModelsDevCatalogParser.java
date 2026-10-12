package com.stonewu.agenteam.mapper.modelpricing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCatalogEntry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 按模型读取目录，避免同时保留原始字节和整棵数据树。 */
@Component
public class ModelsDevCatalogParser {
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(16384).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final ModelsDevPriceMapper prices;

    public ModelsDevCatalogParser(ModelsDevPriceMapper prices) {
        this.prices = prices;
    }

    public List<ModelPriceCatalogEntry> read(InputStream input) throws IOException {
        var result = new ArrayList<ModelPriceCatalogEntry>();
        int providers = 0;
        int[] models = {0};
        try (var parser = json.createParser(input)) {
            require(parser.nextToken() == JsonToken.START_OBJECT);
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME && ++providers <= 512);
                String providerId = checked(parser.currentName(), 160);
                require(parser.nextToken() == JsonToken.START_OBJECT);
                if (providerId.equals("error")) {
                    var error = json.createObjectNode();
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        require(parser.currentToken() == JsonToken.FIELD_NAME);
                        String field = parser.currentName();
                        require(parser.nextToken() != null);
                        if (Set.of("code", "type", "message", "status").contains(field) && parser.currentToken().isScalarValue()) {
                            error.put(field, parser.getValueAsString());
                        } else {
                            parser.skipChildren();
                        }
                    }
                    throw new SourceError(error);
                }
                provider(parser, providerId, result, models);
            }
            require(parser.nextToken() == null && !result.isEmpty());
        }
        return List.copyOf(result);
    }

    private void provider(JsonParser parser, String id, List<ModelPriceCatalogEntry> result, int[] count) throws IOException {
        String name = id;
        String api = null;
        var models = new ArrayList<ModelPriceCatalogEntry>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.FIELD_NAME);
            String field = parser.currentName();
            require(parser.nextToken() != null);
            switch (field) {
                case "name" -> name = checked(parser.getValueAsString(), 200);
                case "api" -> api = checked(parser.getValueAsString(), 2048);
                case "models" -> models(parser, models, count);
                default -> parser.skipChildren();
            }
        }
        for (var model : models) {
            result.add(new ModelPriceCatalogEntry(id, name, model.api() == null ? api : model.api(), model.modelId(),
                model.modelName(), model.canonicalModelId(), model.rules(), model.notices()));
        }
    }

    private void models(JsonParser parser, List<ModelPriceCatalogEntry> result, int[] count) throws IOException {
        require(parser.currentToken() == JsonToken.START_OBJECT);
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.FIELD_NAME && ++count[0] <= 20000);
            String id = checked(parser.currentName(), 256);
            require(parser.nextToken() == JsonToken.START_OBJECT);
            JsonNode model = model(parser);
            var mapped = prices.map(model.get("cost"));
            if (mapped == null || !model.path("modalities").path("output").isArray()) {
                continue;
            }
            boolean text = false;
            for (var output : model.path("modalities").path("output")) {
                text |= output.asText().equals("text");
            }
            if (!text) {
                continue;
            }
            String api = model.path("provider").path("api").isTextual()
                ? checked(model.path("provider").path("api").textValue(), 2048) : null;
            result.add(new ModelPriceCatalogEntry(null, null, api, id, checked(model.path("name").asText(id), 256),
                checked(model.path("canonical_model_id").asText(id), 256), mapped.rules(), mapped.notices()));
        }
    }

    private ObjectNode model(JsonParser parser) throws IOException {
        var result = json.createObjectNode();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.FIELD_NAME);
            String field = parser.currentName();
            require(parser.nextToken() != null);
            switch (field) {
                case "name", "canonical_model_id" -> result.put(field, checked(parser.getValueAsString(), 256));
                case "cost" -> {
                    if (parser.currentToken() == JsonToken.START_OBJECT) {
                        result.set(field, cost(parser, true));
                    } else {
                        parser.skipChildren();
                    }
                }
                case "modalities", "provider" -> {
                    require(parser.currentToken() == JsonToken.START_OBJECT);
                    var values = result.putObject(field);
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        require(parser.currentToken() == JsonToken.FIELD_NAME);
                        String key = parser.currentName();
                        require(parser.nextToken() != null);
                        if (field.equals("provider") && key.equals("api")) {
                            values.put(key, checked(parser.getValueAsString(), 2048));
                        } else if (field.equals("modalities") && key.equals("output")) {
                            require(parser.currentToken() == JsonToken.START_ARRAY);
                            var output = values.putArray(key);
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                require(output.size() < 8 && parser.currentToken() == JsonToken.VALUE_STRING);
                                output.add(checked(parser.getValueAsString(), 32));
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                }
                default -> parser.skipChildren();
            }
        }
        return result;
    }

    private ObjectNode cost(JsonParser parser, boolean nested) throws IOException {
        var result = json.createObjectNode();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.FIELD_NAME);
            String field = parser.currentName();
            require(parser.nextToken() != null);
            if (Set.of("input", "output", "reasoning", "cache_read", "cache_write", "input_audio", "output_audio").contains(field)) {
                if (parser.currentToken().isNumeric()) {
                    result.put(field, parser.getDecimalValue());
                } else {
                    result.putNull(field);
                    parser.skipChildren();
                }
            } else if (field.equals("tiers") && nested) {
                require(parser.currentToken() == JsonToken.START_ARRAY);
                var tiers = result.putArray(field);
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    require(tiers.size() < 32 && parser.currentToken() == JsonToken.START_OBJECT);
                    tiers.add(cost(parser, false));
                }
            } else if (field.equals("context_over_200k") && nested) {
                require(parser.currentToken() == JsonToken.START_OBJECT);
                result.set(field, cost(parser, false));
            } else if (field.equals("tier") && !nested) {
                require(parser.currentToken() == JsonToken.START_OBJECT);
                var tier = result.putObject(field);
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    require(parser.currentToken() == JsonToken.FIELD_NAME);
                    String key = parser.currentName();
                    require(parser.nextToken() != null);
                    if (key.equals("size") && parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                        tier.put(key, parser.getLongValue());
                    } else if (key.equals("type")) {
                        tier.put(key, checked(parser.getValueAsString(), 32));
                    } else {
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();
            }
        }
        return result;
    }

    private String checked(String value, int maximum) throws IOException {
        require(value != null && !value.isBlank() && value.length() <= maximum && value.codePoints().noneMatch(Character::isISOControl));
        return value;
    }

    private void require(boolean condition) throws IOException {
        if (!condition) {
            throw new IOException("价格目录格式或大小不符合读取要求。");
        }
    }

    public static final class SourceError extends IOException {
        private final JsonNode error;

        private SourceError(JsonNode error) {
            super("价格目录返回业务错误。");
            this.error = error;
        }

        public JsonNode error() {
            return error;
        }
    }
}
