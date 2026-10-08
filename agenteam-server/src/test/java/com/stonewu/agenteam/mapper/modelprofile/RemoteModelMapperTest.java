package com.stonewu.agenteam.mapper.modelprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteModelMapperTest {
    private final RemoteModelMapper mapper = new RemoteModelMapper(new ObjectMapper());

    @Test
    void standardListsRemainUnknownAndDuplicateIdentifiersAreRemoved() {
        var models = mapper.read(bytes("""
            {"data":[{"id":"z-model"},{"id":"a-model"},{"id":"z-model"},{"id":""},{"id":123}]}
            """));
        assertEquals(List.of("a-model", "z-model"), models.stream().map(model -> model.id()).toList());
        var model = models.getFirst();
        assertNull(model.maxContextTokens());
        assertNull(model.maxOutputTokens());
        assertNull(model.supportsTools());
        assertNull(model.supportsTemperature());
        assertNull(model.inputTypes());
    }

    @Test
    void readsOnlyPublishedMetadataAndRejectsInconsistentLengths() {
        var model = mapper.read(bytes("""
            {"data":[{"id":"provider/chat","name":"远程模型","context_length":1048576,
            "top_provider":{"max_completion_tokens":65536},"supported_parameters":["tools"],
            "architecture":{"input_modalities":["text","image","audio","text"]}}]}
            """)).getFirst();
        assertEquals(1048576, model.maxContextTokens());
        assertEquals(65536, model.maxOutputTokens());
        assertEquals(List.of("text", "image"), model.inputTypes());
        assertEquals(true, model.supportsTools());
        assertEquals(false, model.supportsTemperature());
        var inconsistent = mapper.read(bytes("""
            {"data":[{"id":"model","context_length":4096,"max_output_tokens":8192}]}
            """)).getFirst();
        assertNull(inconsistent.maxOutputTokens());
        var oversized = mapper.read(bytes("""
            {"data":[{"id":"model","context_length":999999999999,"max_output_tokens":1.5}]}
            """)).getFirst();
        assertNull(oversized.maxContextTokens());
        assertNull(oversized.maxOutputTokens());
    }

    @Test
    void distinguishesAnEmptyListFromAnInvalidResponse() {
        assertTrue(mapper.read(bytes("{\"data\":[]}")).isEmpty());
        for (String body : List.of("", "<html>错误</html>", "{}", "{\"data\":{}}", "{\"data\":[{\"id\":\"\"}]}")) {
            assertEquals("MODEL_LIST_INVALID", assertThrows(ApiException.class, () -> mapper.read(bytes(body))).code());
        }
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
