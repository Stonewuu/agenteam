package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.file.entity.AttachmentText;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 所有附件先完成解析，保存最多五万字符供后续消息提交读取。
 */
@Service
public class AttachmentTextService {
    public static final int MAX_CHARACTERS = 50000;
    private final DocumentParserProcess parser;

    public AttachmentTextService(DocumentParserProcess parser) {
        this.parser = parser;
    }

    public AttachmentText parse(FileRecord file, BooleanSupplier current) {
        StringBuilder text = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean();
        AtomicInteger count = new AtomicInteger();
        try (var parsed = parser.parse(file, current)) {
            parsed.forEach(chunk -> {
                if (count.get() >= MAX_CHARACTERS) {
                    truncated.set(true);
                    return;
                }
                String content = chunk.text().substring(chunk.text().offsetByCodePoints(0, chunk.overlapCharacters()));
                String part = (text.isEmpty() || chunk.overlapCharacters() > 0 ? "" : "\n") + content;
                int remaining = MAX_CHARACTERS - count.get();
                if (part.codePointCount(0, part.length()) > remaining) {
                    part = part.substring(0, part.offsetByCodePoints(0, remaining));
                    truncated.set(true);
                }
                text.append(part);
                count.addAndGet(part.codePointCount(0, part.length()));
            });
        }
        if (text.isEmpty()) {
            throw DocumentParserProcess.failure("FILE_NO_TEXT");
        }
        return new AttachmentText(text.toString(), truncated.get());
    }
}
