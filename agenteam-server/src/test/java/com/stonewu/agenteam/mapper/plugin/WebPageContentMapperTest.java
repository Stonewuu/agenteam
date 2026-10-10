package com.stonewu.agenteam.mapper.plugin;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebPageContentMapperTest {
    @Test
    void extractsMainTextLinksAndDeclaredNonUtf8Encoding() throws Exception {
        String html = "<meta charset=gbk><title>网页标题</title><nav>菜单</nav><main><h1>中文资料</h1>"
            + "<p>第一段 &amp; 第二段</p><a href='/reference'>参考</a><script>不要读取脚本</script>"
            + "<style>不要读取样式</style><p hidden>隐藏内容</p></main>";
        var result = WebPageContentMapper.read(html.getBytes(Charset.forName("GBK")), "text/html", "https://example.com/page");
        assertEquals("网页标题", result.get("title"));
        assertTrue(result.get("content").toString().contains("中文资料"));
        assertTrue(result.get("content").toString().contains("第一段 & 第二段"));
        assertTrue(result.get("content").toString().contains("中文资料\n第一段"));
        assertFalse(result.get("content").toString().matches("(?s).*(菜单|脚本|样式|隐藏内容).*"));
        assertEquals(List.of(Map.of("title", "参考", "url", "https://example.com/reference")), result.get("links"));
    }

    @Test
    void preservesPlainTextAndReportsPagesWithoutReadableContent() throws Exception {
        String text = "第一行\n  第二行\n";
        var plain = WebPageContentMapper.read(text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8", "https://example.com");
        assertEquals(text, plain.get("content"));
        var empty = WebPageContentMapper.read("<body><script>load()</script></body>".getBytes(StandardCharsets.UTF_8), "text/html", "https://example.com");
        assertEquals(true, empty.get("isError"));
        assertEquals("WEB_CONTENT_EMPTY", empty.get("code"));
    }
}
