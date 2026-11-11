package com.stonewu.agenteam.service.announcement;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * 校验公告文档，只保存允许的文字、排版和链接，不接受网页脚本或任意属性。
 */
@Component
public class AnnouncementContent {
    private static final Logger log = LoggerFactory.getLogger(AnnouncementContent.class);
    private static final Set<String> BLOCKS = Set.of("paragraph", "heading", "bulletList", "orderedList", "blockquote",
        "horizontalRule");
    private static final Set<String> MARKS = Set.of("bold", "italic", "underline", "strike", "code", "link");
    private final ObjectMapper json;

    public AnnouncementContent(ObjectMapper json) {
        this.json = json;
    }

    public record Value(String format, String content) {
    }

    public Value validate(String format, String content) {
        if (format == null || format.equals("plain_text")) {
            return new Value("plain_text", ResourceInput.text(content, "content", 20000, true));
        }
        if (!format.equals("rich_text")) {
            throw ApiException.invalidField("contentFormat", Messages.text("error.announcement.announcementWriteRequest.contentFormat.1"));
        }
        String source = ResourceInput.text(content, "content", 200000, true);
        try {
            JsonNode document = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(source);
            var budget = new Budget();
            var normalized = node(document, "", 0, budget);
            if (budget.text.toString().replaceAll("[\\s\\p{Z}\\u200B\\uFEFF]", "").isEmpty()) {
                throw invalid(Messages.text("error.announcement.announcementWriteRequest.content.1"));
            }
            return new Value("rich_text", json.writeValueAsString(normalized));
        } catch (JsonProcessingException failure) {
            log.warn(Messages.log("log.announcement.announcementContent.validate.1"), failure);
            throw ApiException.invalidField("content", Messages.text("error.announcement.announcementContent.validate.1"), failure);
        }
    }

    private ObjectNode node(JsonNode source, String parent, int depth, Budget budget) {
        if (source == null || !source.isObject() || depth > 16 || ++budget.nodes > 5000) {
            throw invalid(Messages.text("error.announcement.announcementWriteRequest.content.2"));
        }
        String type = source.path("type").asText();
        boolean allowed = switch (parent) {
            case "" -> type.equals("doc");
            case "doc", "blockquote" -> BLOCKS.contains(type);
            case "listItem" -> BLOCKS.contains(type);
            case "paragraph", "heading" -> type.equals("text") || type.equals("hardBreak");
            case "bulletList", "orderedList" -> type.equals("listItem");
            default -> false;
        };
        if (!allowed) {
            throw invalid(Messages.text("error.announcement.announcementContent.node.1"));
        }
        var result = json.createObjectNode().put("type", type);
        if (type.equals("text")) {
            if (!source.path("text").isTextual() || source.path("text").asText().isEmpty()) {
                throw invalid(Messages.text("error.announcement.announcementContent.validate.1"));
            }
            String text = source.path("text").asText();
            budget.text.append(text);
            if (budget.text.length() > 20000) {
                throw invalid(Messages.text("error.announcement.announcementContent.node.2"));
            }
            result.put("text", text);
            copyMarks(source, result);
        } else if (type.equals("heading")) {
            int level = source.path("attrs").path("level").asInt(0);
            if (level < 1 || level > 3) {
                throw invalid(Messages.text("error.announcement.announcementContent.node.3"));
            }
            result.putObject("attrs").put("level", level);
        } else if (type.equals("orderedList")) {
            int start = source.path("attrs").path("start").asInt(1);
            if (start < 1 || start > 1000000) {
                throw invalid(Messages.text("error.announcement.announcementContent.node.4"));
            }
            result.putObject("attrs").put("start", start);
        }
        JsonNode children = source.path("content");
        boolean leaf = type.equals("text") || type.equals("hardBreak") || type.equals("horizontalRule");
        if (leaf) {
            if (!children.isMissingNode()) {
                throw invalid(Messages.text("error.announcement.announcementContent.validate.1"));
            }
        } else {
            if ((!children.isMissingNode() && !children.isArray()) ||
                (Set.of("doc", "blockquote", "bulletList", "orderedList", "listItem")
                    .contains(type) && children.isEmpty())) {
                throw invalid(Messages.text("error.announcement.announcementContent.validate.1"));
            }
            if (type.equals("listItem") && !children.path(0).path("type").asText().equals("paragraph")) {
                throw invalid(Messages.text("error.announcement.announcementContent.node.5"));
            }
            var output = result.putArray("content");
            for (var child : children) {
                output.add(node(child, type, depth + 1, budget));
            }
        }
        return result;
    }

    private void copyMarks(JsonNode source, ObjectNode result) {
        JsonNode marks = source.path("marks");
        if (marks.isMissingNode()) {
            return;
        }
        if (!marks.isArray() || marks.size() > 6) {
            throw invalid(Messages.text("error.announcement.announcementContent.copyMarks.1"));
        }
        var output = result.putArray("marks");
        for (var mark : marks) {
            String type = mark.path("type").asText();
            if (!MARKS.contains(type)) {
                throw invalid(Messages.text("error.announcement.announcementContent.copyMarks.2"));
            }
            var normalized = output.addObject().put("type", type);
            if (type.equals("link")) {
                String href = mark.path("attrs").path("href").asText("");
                validateLink(href);
                normalized.putObject("attrs").put("href", href);
            }
        }
    }

    private void validateLink(String href) {
        if (href.length() > 2048 || href.matches(".*[\\s\\p{Cntrl}<>].*")) {
            throw invalid(Messages.text("error.announcement.announcementContent.validateLink.1"));
        }
        try {
            var uri = new URI(href);
            boolean web = ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(
                uri.getScheme())) && uri.getHost() != null;
            boolean email = "mailto".equalsIgnoreCase(uri.getScheme()) && uri.getSchemeSpecificPart().contains("@");
            if (!web && !email) {
                throw invalid(Messages.text("error.announcement.announcementContent.validateLink.2"));
            }
        } catch (URISyntaxException failure) {
            log.warn(Messages.log("log.announcement.announcementContent.validateLink.1"), failure);
            throw ApiException.invalidField("content", Messages.text("error.announcement.announcementContent.validateLink.1"), failure);
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.invalidField("content", message);
    }

    private static final class Budget {
        private int nodes;
        private final StringBuilder text = new StringBuilder();
    }
}
