package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.file.entity.AttachmentText;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 平台文档复用原解析器；CSV 和 JSON 只读取已经校验编码的文字，不执行公式或代码。 */
@Service
public class CollaborationAttachmentParser {
    private final AttachmentTextService documents;
    private final FileContentStorage storage;

    public CollaborationAttachmentParser(AttachmentTextService documents, FileContentStorage storage) {
        this.documents = documents;
        this.storage = storage;
    }

    public AttachmentText parse(FileRecord file, BooleanSupplier current) {
        if (!Set.of("csv", "json").contains(FileUploadPolicy.extension(file.originalName()))) {
            return documents.parse(file, current);
        }
        var text = new StringBuilder();
        boolean truncated = false;
        try (var reader = new InputStreamReader(storage.open(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (!current.getAsBoolean()) {
                    throw DocumentParserProcess.failure("FILE_PROCESSING_CANCELLED");
                }
                int remaining = AttachmentTextService.MAX_CHARACTERS - text.length();
                text.append(buffer, 0, Math.min(remaining, count));
                if (count > remaining) {
                    truncated = true;
                    break;
                }
            }
        } catch (IOException failure) {
            throw FileStorageKeys.storageUnavailable(failure);
        }
        if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
        }
        if (text.isEmpty()) {
            throw DocumentParserProcess.failure("FILE_NO_TEXT");
        }
        return new AttachmentText(text.toString(), truncated);
    }
}
