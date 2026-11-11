package com.stonewu.agenteam.service.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.i18n.RequestLanguageResolver;
import com.stonewu.agenteam.model.i18n.entity.UiLanguage;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.ResourceBundle;
import com.stonewu.tool.api.ToolQuery;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @Test
    void logTemplatesStayEnglishAndKeepOriginalArguments() {
        String key = "log.workspace.workspaceToolService.invoke.2";
        String argument = "用户提供的编号 {0}";
        var cause = new IllegalStateException("供应商原始错误");
        for (var language : UiLanguage.values()) {
            try (var ignored = MessageLanguage.use(language)) {
                var formatted = MessageFormatter.arrayFormat(Messages.log(key),
                    new Object[]{argument, "call-1", cause});
                assertTrue(formatted.getMessage().startsWith("Sandbox output temporary directory cleanup failed;"));
                assertTrue(formatted.getMessage().contains(argument));
                assertSame(cause, formatted.getThrowable());
            }
        }
    }

    @Test
    void commandFailuresCanBeReadInEitherLanguageWithoutChangingStoredOutput() {
        for (String key : List.of("workspace.command.capacityExceeded", "workspace.command.timedOut")) {
            String stored = Messages.source(key);
            try (var ignored = MessageLanguage.use(UiLanguage.ENGLISH)) {
                assertEquals(Messages.text(key), Messages.localize(stored, UiLanguage.ENGLISH));
                assertFalse(Messages.text(key).matches(".*[\\p{IsHan}].*"));
                assertEquals(stored, Messages.localize(Messages.text(key), UiLanguage.CHINESE));
            }
        }
    }

    @Test
    void standaloneToolApiKeepsItsMessagesWithoutDependingOnTheServer() {
        assertEquals("The tool query result is incomplete.", ResourceBundle.getBundle("com.stonewu.tool.api.messages",
            Locale.ENGLISH).getString("query.incomplete"));
        assertEquals("工具查询结果不完整", ResourceBundle.getBundle("com.stonewu.tool.api.messages",
            Locale.SIMPLIFIED_CHINESE).getString("query.incomplete"));
        assertThrows(IllegalArgumentException.class, () -> new ToolQuery(null, Map.of()));
    }

    @Test
    void languagePreferencesAndNestedScopesDoNotLeakAcrossRequests() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr;q=1,en-GB;q=0.8,zh-CN;q=0.2");
        assertEquals(UiLanguage.ENGLISH, RequestLanguageResolver.resolve(request));
        request.setCookies(new Cookie("agenteam-locale", "zh-CN"));
        assertEquals(UiLanguage.CHINESE, RequestLanguageResolver.resolve(request));
        assertEquals(UiLanguage.CHINESE, MessageLanguage.negotiate("en;q=0,zh;q=0.5"));
        assertEquals(UiLanguage.CHINESE, MessageLanguage.negotiate("invalid;q=oops"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(
                () -> scoped(UiLanguage.ENGLISH), () -> scoped(UiLanguage.CHINESE)));
            assertNotEquals(results.get(0).get(), results.get(1).get());
        }
        assertEquals(UiLanguage.CHINESE, MessageLanguage.current());
    }

    private String scoped(UiLanguage language) {
        try (var outer = MessageLanguage.use(language)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = MessageLanguage.use(UiLanguage.ENGLISH)) {
                    throw new IllegalStateException("测试作用域退出");
                }
            });
            assertEquals(language, MessageLanguage.current());
            return Messages.text("model.apiKeyRequired");
        }
    }

    @Test
    void argumentsArePreservedWithoutRecursiveReplacement() {
        String value = "设计 {0} '$1' \\ \"sample\"";
        // 从固定原文反向翻译旧记录，参数中的标点和另一份占位符都不应被解释。
        String original = "未找到版本 " + value + " 的内容加密密钥，请检查 agenteam.security.encryption-keys 和 agenteam.security.active-encryption-key。";
        String english = Messages.localize(original, UiLanguage.ENGLISH);
        assertTrue(english.startsWith("Content encryption key version " + value));
        assertEquals(original, Messages.localize(english, UiLanguage.CHINESE));
        assertEquals("未知的用户内容", Messages.localize("未知的用户内容", UiLanguage.ENGLISH));
        assertThrows(IllegalArgumentException.class, () -> Messages.text("missing.catalog.entry"));
    }

    @Test
    void englishValidationUsesCatalogsAndKeepsTheSameFields() {
        var invalid = new ModelProfileWriteRequest("provider", "我的模型", "model",
            new ModelCapabilities(true, true, 0, 32768, List.of("text", "text")), true);
        try (var ignored = MessageLanguage.use(UiLanguage.ENGLISH)) {
            var failure = assertThrows(ApiException.class, () -> InputValidation.validate(invalid));
            assertEquals(List.of("Must not be less than 128."), failure.fieldErrors().get("capabilities.maxOutputTokens"));
            assertTrue(failure.fieldErrors().containsKey("capabilities.inputTypes"));
            assertFalse(failure.getReason().matches(".*[\\p{IsHan}].*"));
        }
        var chinese = assertThrows(ApiException.class, () -> InputValidation.validate(invalid));
        assertEquals(List.of("不能小于 128。"), chinese.fieldErrors().get("capabilities.maxOutputTokens"));
    }

    @Test
    void responseTranslatesStoredErrorsWithoutRewritingDiagnosticData() {
        var request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader("Accept-Language", "en");
        var failure = new ApiException(HttpStatus.BAD_REQUEST, "MODEL_KEY_REQUIRED",
            Messages.source("model.apiKeyRequired"), Map.of("name", "模型密钥不能为空"), Map.of());
        var result = new ApiResponses(Clock.systemUTC(), new ObjectMapper()).failure(failure, request).getBody().error();
        assertEquals(Messages.in(UiLanguage.ENGLISH, "model.apiKeyRequired"), result.message());
        assertEquals("模型密钥不能为空", result.details().get("name"));
        assertEquals("MODEL_KEY_REQUIRED", result.code());
    }
}
