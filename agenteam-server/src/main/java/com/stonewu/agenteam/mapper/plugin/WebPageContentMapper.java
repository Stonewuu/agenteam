package com.stonewu.agenteam.mapper.plugin;

import org.jsoup.Jsoup;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 网页只提取正文和链接，脚本与样式不进入模型上下文；保留服务器声明的文字编码。 */
public final class WebPageContentMapper {
    private WebPageContentMapper() {
    }

    public static Map<String, Object> read(byte[] bytes, String contentType, String url) throws IOException {
        var type = MediaType.parseMediaType(contentType);
        var charset = type.getCharset();
        if (!type.isCompatibleWith(MediaType.TEXT_HTML) && !type.toString().startsWith("application/xhtml+xml")) {
            String text = (charset == null ? StandardCharsets.UTF_8 : charset).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return Map.of("url", url, "content", text, "mediaType", type.getType() + "/" + type.getSubtype());
        }
        var document = Jsoup.parse(new ByteArrayInputStream(bytes), charset == null ? null : charset.name(), url);
        document.select("script,style,template,noscript,svg,canvas,[hidden],[aria-hidden=true]").remove();
        var main = document.selectFirst("main,article,[role=main]");
        var root = main == null ? document.body() : main;
        root.select("p,div,li,h1,h2,h3,h4,h5,h6,tr,section,blockquote,pre").forEach(element -> element.appendText("\n"));
        root.select("td,th").forEach(element -> element.appendText(" "));
        String content = root.wholeText().replaceAll("[\\t\\x0B\\f\\r ]+", " ")
            .replaceAll(" *\\n *", "\n").replaceAll("\n{3,}", "\n\n").trim();
        var links = new ArrayList<Map<String, String>>();
        for (var link : root.select("a[href]")) {
            if (links.size() == 40) {
                break;
            }
            String href = link.absUrl("href");
            if (!link.text().isBlank() && href.length() <= 2048 && (href.startsWith("https://") || href.startsWith("http://"))) {
                String title = link.text();
                links.add(Map.of("title", title.substring(0, Math.min(200, title.length())), "url", href));
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("url", url);
        result.put("title", document.title());
        result.put("content", content);
        result.put("mediaType", "text/html");
        result.put("links", List.copyOf(links));
        if (content.isBlank()) {
            result.put("isError", true);
            result.put("code", "WEB_CONTENT_EMPTY");
            result.put("content", "网页没有可直接读取的正文。如有命令执行工具，可用 agenteam-browser read 网址 等待页面脚本加载后读取。");
        }
        return result;
    }
}
