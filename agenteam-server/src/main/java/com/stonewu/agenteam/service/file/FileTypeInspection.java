package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 扫描前只检查文件特征和文字编码，复杂文档留给独立解析进程。
 */
@Service
public class FileTypeInspection {
    private final FileContentStorage storage;

    public FileTypeInspection(FileContentStorage storage) {
        this.storage = storage;
    }

    public void verify(FileRecord file) {
        String type = FileUploadPolicy.extension(file.originalName());
        try (var input = storage.open(file)) {
            String signature = new String(input.readNBytes(8), StandardCharsets.ISO_8859_1);
            if (type.equals("pdf") && signature.startsWith("%PDF-")) {
                return;
            }
            if (Set.of("docx", "xlsx", "pptx").contains(type) && signature.startsWith("PK\003\004")) {
                return;
            }
            if (!Set.of("txt", "md", "csv", "json").contains(type) || signature.startsWith(
                "MZ") || signature.startsWith("PK") || signature.startsWith("%PDF-")) {
                throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
            }
        } catch (IOException unavailable) {
            throw FileStorageKeys.storageUnavailable();
        }
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(storage.open(file), decoder)) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    if (Character.isISOControl(
                        buffer[i]) && buffer[i] != '\n' && buffer[i] != '\r' && buffer[i] != '\t') {
                        throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
                    }
                }
            }
        } catch (CharacterCodingException invalid) {
            throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
        } catch (IOException unavailable) {
            throw FileStorageKeys.storageUnavailable();
        }
    }
}
