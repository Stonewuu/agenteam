package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.ConcurrentLruCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 一次遍历同时统计环境和目录，只在单独的统计请求中等待磁盘扫描。 */
@Service
public class EnvironmentFileStatistics {
    static final int MAX_CACHED_FOLDERS = 4096;
    private static final long MAX_AGE = Duration.ofSeconds(30).toNanos();
    private final ConcurrentLruCache<String, Slot> workspaces = new ConcurrentLruCache<>(16, unused -> new Slot());
    private final ProjectWorkspaceLayout layout;

    public record Usage(long fileCount, long totalBytes, String sampleId) {
    }

    record Snapshot(Usage usage, Map<String, Long> folders, String revision, long recordedAt) {
    }

    private static final class Slot {
        private volatile Snapshot latest;
    }

    public EnvironmentFileStatistics(ProjectWorkspaceLayout layout) {
        this.layout = layout;
    }

    /** 列表读取已经算好的结果；第一次打开目录时不在这里遍历子目录。 */
    Map<String, Long> cachedFolders(String workspace) {
        var recent = workspaces.get(workspace).latest;
        if (recent == null) {
            return Map.of();
        }
        try {
            return recent.revision().equals(layout.statisticsRevision(workspace)) ? recent.folders() : Map.of();
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    Snapshot current(String workspace, Path root, String refreshAfter) {
        Slot slot = workspaces.get(workspace);
        synchronized (slot) {
            try {
                String revision = layout.statisticsRevision(workspace);
                var latest = slot.latest;
                if (latest != null && System.nanoTime() - latest.recordedAt() < MAX_AGE
                    && latest.revision().equals(revision) && !latest.usage().sampleId().equals(refreshAfter)) {
                    return latest;
                }
                var folders = new HashMap<String, Long>();
                var totals = Files.notExists(root, LinkOption.NOFOLLOW_LINKS) ? new EnvironmentFileUsage(0, 0, 0)
                    : EnvironmentFileUsage.read(root, folders);
                if (!revision.equals(layout.statisticsRevision(workspace))) {
                    throw new ApiException(HttpStatus.CONFLICT, "ENVIRONMENT_FILES_CHANGED", Messages.text("error.environment.usageChanged"));
                }
                var usage = new Usage(totals.fileCount(), totals.totalBytes(), UUID.randomUUID().toString());
                var result = new Snapshot(usage, Map.copyOf(folders), revision, System.nanoTime());
                slot.latest = result;
                return result;
            } catch (IOException failure) {
                throw WorkspacePaths.io(failure);
            }
        }
    }
}
