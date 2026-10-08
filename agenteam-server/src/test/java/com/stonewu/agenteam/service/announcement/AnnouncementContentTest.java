package com.stonewu.agenteam.service.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnnouncementContentTest {
    private final AnnouncementContent content = new AnnouncementContent(new ObjectMapper());

    @Test
    void preservesExistingPlainTextWithoutInterpretingMarkup() {
        var result = content.validate(null, "<b>旧公告</b>\n**原始文字**");
        assertEquals("plain_text", result.format());
        assertEquals("<b>旧公告</b>\n**原始文字**", result.content());
    }

    @Test
    void savesSupportedFormattingAndDropsUnrelatedAttributes() {
        var result = content.validate("rich_text", """
            {"type":"doc","content":[{"type":"heading","attrs":{"level":2,"onclick":"无效属性"},
            "content":[{"type":"text","text":"维护通知","marks":[{"type":"bold"}]}]},
            {"type":"paragraph","content":[{"type":"text","text":"帮助页面","marks":[
            {"type":"link","attrs":{"href":"https://example.com/help","target":"任意目标"}}]}]}]}
            """);
        assertEquals("rich_text", result.format());
        assertTrue(result.content().contains("https://example.com/help"));
        assertFalse(result.content().contains("onclick"));
        assertFalse(result.content().contains("target"));
    }

    @Test
    void rejectsExecutableLinksAndUnsupportedNodes() {
        assertThrows(ApiException.class, () -> content.validate("rich_text", """
            {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"点击",
            "marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}]}
            """));
        assertThrows(ApiException.class, () -> content.validate("rich_text", """
            {"type":"doc","content":[{"type":"script","text":"无效节点"}]}
            """));
    }

    @Test
    void rejectsEmptyAndExcessiveText() {
        assertThrows(ApiException.class, () -> content.validate("rich_text", """
            {"type":"doc","content":[{"type":"paragraph"}]}
            """));
        assertThrows(ApiException.class, () -> content.validate("rich_text", document("字".repeat(20001))));
        assertEquals("rich_text", content.validate("rich_text", document("字".repeat(20000))).format());
    }

    @Test
    void rejectsMalformedStructureAndUnboundedNesting() {
        String nested = "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"正文\"}]}";
        for (int index = 0; index < 20; index++) {
            nested = "{\"type\":\"blockquote\",\"content\":[" + nested + "]}";
        }
        String source = "{\"type\":\"doc\",\"content\":[" + nested + "]}";
        assertThrows(ApiException.class, () -> content.validate("rich_text", source));
        assertThrows(ApiException.class, () -> content.validate("rich_text", "{\"type\":\"doc\",\"content\":{}}"));
        assertThrows(ApiException.class, () -> content.validate("html", "正文"));
    }

    private String document(String text) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]}";
    }
}
