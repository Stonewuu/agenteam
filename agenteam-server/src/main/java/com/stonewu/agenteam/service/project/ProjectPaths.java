package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.http.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 项目目录在 Windows 和 Linux 使用相同的名称约束。
 */
public final class ProjectPaths {
    private ProjectPaths() {
    }

    public static String workspaceId(String enterprise, String user) {
        return digest(enterprise + "\n" + user);
    }

    public static String directory(String requested, String id) {
        String value = requested == null || requested.isBlank() ? id : Normalizer.normalize(
            requested.strip().replace('\\', '/'), Normalizer.Form.NFC);
        if (value.startsWith("projects/")) {
            value = value.substring(9);
        }
        if (value.length() > 256 || value.split("/", -1).length > 8) {
            throw invalid();
        }
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(" ") || part.endsWith(".")
                || part.getBytes(StandardCharsets.UTF_8).length > 240 || part.matches(".*[<>:\"|?*].*")
                || part.codePoints().anyMatch(Character::isISOControl)
                || part.split("\\.", 2)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw invalid();
            }
        }
        return "projects/" + value;
    }

    public static String directoryKey(String directory) {
        return directory.toLowerCase(Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(Messages.text("error.project.projectPaths.digest.1"), failure);
        }
    }

    private static ApiException invalid() {
        return ApiException.invalidField("directory",
            Messages.text("error.project.projectPaths.invalid.1"));
    }
}
