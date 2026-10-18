package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class EnvironmentFileService {
    public record Entry(String name, String path, boolean directory, long size, String modifiedAt) {
    }
    public record Directory(String path, List<Entry> entries, Integer nextOffset, int total) {
    }
    public record Preview(String name, String path, long size, boolean binary, boolean truncated, String content) {
    }

    private final EnvironmentWorkspaceService workspaces;

    public EnvironmentFileService(EnvironmentWorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    public Directory list(AuthContext actor, String path, int offset) {
        return list(actor, path, offset, "", "name");
    }

    public Directory list(AuthContext actor, String path, int offset, String query, String sort) {
        if (offset < 0 || offset > 100000) {
            throw ApiException.invalidField("offset", "请重新加载文件列表。");
        }
        String logical = WorkspacePaths.projectLogical(path);
        var files = workspaces.files(actor);
        if (!Files.isDirectory(files.root())) {
            return new Directory("/workspace", List.of(), null, 0);
        }
        if (query.length() > 200) {
            throw ApiException.invalidField("query", "搜索内容不能超过 200 个字符。");
        }
        Comparator<Entry> order = switch (sort) {
            case "modified" -> Comparator.comparing(Entry::modifiedAt).reversed();
            case "size" -> Comparator.comparingLong(Entry::size).reversed();
            default -> Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT));
        };
        var all = files.files(logical, false).stream()
            .map(file -> new Entry(name(file.path()), "/workspace/" + file.path(), file.isDirectory(), file.size(), file.modifiedAt()))
            .filter(file -> file.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
            .sorted(Comparator.comparing(Entry::directory).reversed().thenComparing(order).thenComparing(Entry::name)).toList();
        int start = Math.min(offset, all.size());
        int end = Math.min(start + 100, all.size());
        return new Directory(logical.isEmpty() ? "/workspace" : "/workspace/" + logical, all.subList(start, end),
            end < all.size() ? end : null, all.size());
    }

    public Preview preview(AuthContext actor, String path) {
        String logical = WorkspacePaths.projectLogical(path);
        byte[] bytes = bytes(actor, logical);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException binary) {
            return new Preview(name(logical), "/workspace/" + logical, bytes.length, true, false, "");
        }
        boolean binary = text.indexOf('\0') >= 0;
        int length = Math.min(text.length(), 100000);
        if (length > 0 && Character.isHighSurrogate(text.charAt(length - 1))) {
            length--;
        }
        return new Preview(name(logical), "/workspace/" + logical, bytes.length, binary, length < text.length(),
            binary ? "" : text.substring(0, length));
    }

    public byte[] bytes(AuthContext actor, String path) {
        return workspaces.files(actor).bytes(WorkspacePaths.projectLogical(path));
    }

    public static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
