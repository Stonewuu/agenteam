package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 技能只导入受限的 UTF-8 文字文件，解析内容不启动任何文件中的代码。
 */
@Component
public class SkillFileValidation {
    public static final long MAX_BYTES = 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength((int) MAX_BYTES).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public String read(FileRecord file, FileContentStorage storage) {
        if (!file.purpose().equals("skill_import") || file.sizeBytes() > MAX_BYTES) {
            throw invalid();
        }
        try (var input = storage.open(file)) {
            byte[] bytes = input.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES || bytes.length != file.sizeBytes()) {
                throw invalid();
            }
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.sha256())) {
                throw invalid();
            }
            String text = decode(bytes);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            if (text.isBlank() || text.codePoints()
                .anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')) {
                throw invalid();
            }
            if (FileUploadPolicy.mediaType(file.purpose(), file.originalName())
                .equals("application/json") && !json.readTree(text).isObject()) {
                throw invalid();
            }
            return text;
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw invalid();
        }
    }

    private String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_TYPE_INVALID",
            "文件内容不是完整的 UTF-8 文字，请重新导出后上传。");
    }
}
