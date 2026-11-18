package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import com.stonewu.agenteam.service.project.ProjectPaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class EnvironmentFileService {
    public record Entry(String name, String path, boolean directory, Long size, String modifiedAt,
                        String projectName, boolean deletable) {
    }
    public record Directory(String path, List<Entry> entries, Integer nextOffset, int total) {
    }
    public record Preview(String name, String path, long size, boolean binary, boolean truncated, String content) {
    }

    private final EnvironmentWorkspaceService workspaces;
    private final ProjectMetadataService projects;
    private final EnvironmentFileStatistics statistics;

    public EnvironmentFileService(EnvironmentWorkspaceService workspaces, ProjectMetadataService projects, EnvironmentFileStatistics statistics) {
        this.workspaces = workspaces;
        this.projects = projects;
        this.statistics = statistics;
    }

    public Directory list(AuthContext actor, String path, int offset) {
        return list(actor, path, offset, "", "name");
    }

    public Directory list(AuthContext actor, String path, int offset, String query, String sort) {
        if (offset < 0 || offset > 100000) {
            throw ApiException.invalidField("offset", Messages.text("error.environment.environmentFileService.list.1"));
        }
        String logical = WorkspacePaths.projectLogical(path);
        var files = workspaces.files(actor);
        if (logical.startsWith("projects/") && Files.notExists(files.root().resolve(logical), LinkOption.NOFOLLOW_LINKS)) {
            projects.prepareMissingDirectory(actor, logical);
        }
        if (logical.isEmpty() && Files.notExists(files.root(), LinkOption.NOFOLLOW_LINKS)) {
            return new Directory("/workspace", List.of(), null, 0);
        }
        if (Files.notExists(files.root(), LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(files.resolve(logical, false), LinkOption.NOFOLLOW_LINKS)) {
            throw missingDirectory();
        }
        if (query.length() > 200) {
            throw ApiException.invalidField("query", Messages.text("error.environment.environmentFileService.list.2"));
        }
        Comparator<Entry> order = switch (sort) {
            case "modified" -> Comparator.comparing(Entry::modifiedAt).reversed();
            case "size" -> Comparator.comparing(Entry::size, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT));
        };
        var directories = new EnvironmentProjectDirectories(projects, actor, logical);
        String search = query.toLowerCase(Locale.ROOT);
        String workspace = ProjectPaths.workspaceId(actor.enterpriseId(), actor.userId());
        var sizes = statistics.cachedFolders(workspace);
        var matches = files.directoryEntries(logical).stream()
            .map(file -> new Entry(name(file.path()), "/workspace/" + file.path(), file.isDirectory(),
                file.isDirectory() ? sizes.get(file.path()) : Long.valueOf(file.size()), file.modifiedAt(),
                file.isDirectory() ? directories.projectName(file.path()) : null, !directories.protectedPath(file.path())))
            .filter(file -> file.name().toLowerCase(Locale.ROOT).contains(search)
                || file.projectName() != null && file.projectName().toLowerCase(Locale.ROOT).contains(search));
        var all = matches.sorted(Comparator.comparing(Entry::directory).reversed().thenComparing(order)
            .thenComparing(Entry::name)).toList();
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

    public EnvironmentFileStatistics.Usage usage(AuthContext actor, String refreshAfter) {
        var root = workspaces.files(actor).root();
        return statistics.current(ProjectPaths.workspaceId(actor.enterpriseId(), actor.userId()), root, refreshAfter).usage();
    }

    static ApiException missingDirectory() {
        return new ApiException(HttpStatus.NOT_FOUND, "ENVIRONMENT_DIRECTORY_MISSING", Messages.text("error.environment.missingDirectory"));
    }

    public byte[] bytes(AuthContext actor, String path) {
        return workspaces.files(actor).bytes(WorkspacePaths.projectLogical(path));
    }

    public static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
