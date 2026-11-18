package com.stonewu.agenteam.service.environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.service.project.ProjectPaths;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentFileStatisticsTest {
    @TempDir
    Path root;

    @Test
    void sharesConcurrentCountsAndKeepsOverallUsageWhenThereAreTooManyFolders() throws Exception {
        var layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), new ObjectMapper());
        String workspace = ProjectPaths.workspaceId("enterprise", "owner");
        layout.prepareWorkspace(workspace, false);
        Path files = layout.files(workspace);
        Files.write(files.resolve("note"), new byte[37]);
        for (int i = 0; i <= EnvironmentFileStatistics.MAX_CACHED_FOLDERS; i++) {
            Files.createDirectory(files.resolve("directory-" + i));
        }
        var statistics = new EnvironmentFileStatistics(layout);
        assertTrue(statistics.cachedFolders(workspace).isEmpty());
        var tasks = new ArrayList<Callable<EnvironmentFileStatistics.Snapshot>>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> statistics.current(workspace, files, null));
        }
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var samples = new HashSet<String>();
            for (var future : requests.invokeAll(tasks)) {
                var snapshot = future.get();
                samples.add(snapshot.usage().sampleId());
                assertEquals(1, snapshot.usage().fileCount());
                assertEquals(37, snapshot.usage().totalBytes());
                assertTrue(snapshot.folders().isEmpty());
            }
            assertEquals(1, samples.size());
        }
    }
}
