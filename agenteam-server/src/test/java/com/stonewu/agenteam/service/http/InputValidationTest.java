package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.model.modelprofile.request.ModelProviderWriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InputValidationTest {
    @Test
    void requestObjectsRejectMissingUnknownAndIncorrectlyTypedFields() {
        var input = new ObjectMapper().createObjectNode().put("name", "本机模型服务").put("protocol", "openai")
            .put("baseUrl", "http://127.0.0.1:1234/v1").put("enabled", true);
        assertDoesNotThrow(() -> InputValidation.read(input, ModelProviderWriteRequest.class, ""));
        input.put("unexpected", true);
        var unknown = assertThrows(ApiException.class, () -> InputValidation.read(input, ModelProviderWriteRequest.class, ""));
        assertTrue(unknown.fieldErrors().containsKey("unexpected"));
        assertTrue(unknown.getReason().contains("unexpected"));
        assertTrue(unknown.getReason().contains("不支持此字段"));
        input.remove("unexpected");
        input.put("enabled", "true");
        var wrongType = assertThrows(ApiException.class, () -> InputValidation.read(input, ModelProviderWriteRequest.class, ""));
        assertTrue(wrongType.fieldErrors().get("enabled").getFirst().contains("布尔值"));
        input.remove("enabled");
        assertTrue(assertThrows(ApiException.class, () -> InputValidation.read(input, ModelProviderWriteRequest.class, "")).fieldErrors().containsKey("enabled"));
    }

    @Test
    void nestedModelCapabilitiesAreValidatedByTheirJavaConstraints() {
        var valid = new ModelProfileWriteRequest("provider", "对话模型", "model", new ModelCapabilities(true, true, 8192, 32768, List.of("text")), true);
        assertDoesNotThrow(() -> InputValidation.validate(valid));
        var invalid = new ModelProfileWriteRequest("provider", "对话模型", "model", new ModelCapabilities(true, true, 0, 32768, List.of("text", "text")), true);
        var failure = assertThrows(ApiException.class, () -> InputValidation.validate(invalid));
        assertTrue(failure.fieldErrors().containsKey("capabilities.maxOutputTokens"));
        assertEquals(List.of("不能小于 128。"), failure.fieldErrors().get("capabilities.maxOutputTokens"));
        assertTrue(failure.fieldErrors().containsKey("capabilities.inputTypes"));
    }

    @Test
    void nestedTypeErrorsNameTheFieldWithoutReturningRejectedValues() {
        var json = new ObjectMapper();
        var input = json.createObjectNode().put("providerId", "provider").put("name", "模型").put("modelName", "model").put("enabled", true);
        input.putObject("capabilities").put("supportsTools", true).put("supportsTemperature", true)
            .put("maxOutputTokens", "不能回显的输入内容").put("maxContextTokens", 32768).putArray("inputTypes").add("text");
        var invalid = assertThrows(ApiException.class, () -> InputValidation.read(input, ModelProfileWriteRequest.class, "config"));
        assertEquals(List.of("需要填写整数，不能使用文字或小数。"), invalid.fieldErrors().get("config.capabilities.maxOutputTokens"));
        assertFalse(invalid.getReason().contains("不能回显的输入内容"));
        assertFalse(invalid.getReason().contains("ModelProfileWriteRequest"));
    }

    @Test
    void nestedArrayErrorsMatchFormFieldNamesAndStateTheAllowedLength() {
        var valid = new ModelProfileWriteRequest("provider", "模型", "model", new ModelCapabilities(true, true, 8192, 32768, List.of("")), true);
        var invalid = assertThrows(ApiException.class, () -> InputValidation.validate(valid));
        assertTrue(invalid.fieldErrors().containsKey("capabilities.inputTypes.0"));
        var input = new ObjectMapper().createObjectNode().put("name", "名".repeat(81)).put("protocol", "openai")
            .put("baseUrl", "http://127.0.0.1:1234/v1").put("enabled", true);
        var tooLong = assertThrows(ApiException.class, () -> InputValidation.read(input, ModelProviderWriteRequest.class, ""));
        assertTrue(tooLong.fieldErrors().get("name").getFirst().contains("80"));
    }

    @Test
    void controllerBodyErrorsKeepTheirStatusAndProvideFieldDetails() throws Exception {
        var json = new ObjectMapper();
        var invalid = assertThrows(ApiException.class, () -> InputValidation.read(json.createObjectNode()
            .put("unexpected", "不能回显的内部内容"), ModelProviderWriteRequest.class, ""));
        var unreadable = new HttpMessageNotReadableException("内部解析异常", invalid.getCause(), new MockHttpInputMessage(new byte[0]));
        var response = new ApiResponses(Clock.systemUTC(), json).failure(unreadable, new MockHttpServletRequest("POST", "/api/v1/example"));
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().error().fieldErrors().containsKey("unexpected"));
        String body = json.writeValueAsString(response.getBody());
        assertFalse(body.contains("不能回显的内部内容"));
        assertFalse(body.contains("ModelProviderWriteRequest"));
    }

    @Test
    void frameworkBodyTypeErrorsUseTheSameFieldMessages() {
        var json = JsonMapper.builder().build();
        var invalid = assertThrows(MismatchedInputException.class, () -> json.readValue("{\"enabled\":[]}", ModelProviderWriteRequest.class));
        var unreadable = new HttpMessageNotReadableException("内部解析异常", invalid, new MockHttpInputMessage(new byte[0]));
        var response = new ApiResponses(Clock.systemUTC(), new ObjectMapper()).failure(unreadable, new MockHttpServletRequest("POST", "/api/v1/example"));
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().error().fieldErrors().get("enabled").getFirst().contains("布尔值"));
    }
}
