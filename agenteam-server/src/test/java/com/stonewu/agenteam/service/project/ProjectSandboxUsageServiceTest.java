package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxLifecycleSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.mapper.execution.RunJobMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProjectSandboxUsageServiceTest {
    @TempDir
    Path root;

    @Test
    void aMissingHeartbeatRequiresCheckingTheActualJobAndOldReleasesDoNotRemoveNewAttempts() throws Exception {
        var clock = new UserSandboxLifecycleTest.TestClock();
        var layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), new ObjectMapper());
        var registry = new UserSandboxRegistry(layout);
        var jobs = mock(RunJobMapper.class);
        var service = new ProjectSandboxUsageService(layout, registry, jobs, new SandboxLifecycleSettings(900, 30, 60, 10), clock);
        var project = new ProjectLocation(ProjectPaths.workspaceId("enterprise", "user"), "project", "enterprise", "user", "projects/project");
        layout.prepareWorkspace(project.workspaceId(), false);
        var lease = new JobLease("job", "enterprise", "user", "run", "worker", 2, clock.instant().plusSeconds(600));
        var old = new JobLease("job", "enterprise", "user", "run", "old-worker", 1, clock.instant());
        var run = mock(RunRecord.class);
        when(run.id()).thenReturn("run");
        when(run.leaseVersion()).thenReturn(2L);
        service.started(lease);
        try (var control = new ToolCallControl(() -> {
        })) {
            service.touch(run, "child-session", project, Duration.ofSeconds(5), control);
        }
        service.release(old);
        assertEquals(1, registry.usages(project.workspaceId()).size());
        clock.advance(61);
        when(jobs.valid(any(), any(), eq(false))).thenReturn(true);
        service.reconcile();
        assertEquals(1, registry.usages(project.workspaceId()).size(), "失联本身不能作为释放依据");
        when(jobs.valid(any(), any(), eq(false))).thenReturn(false);
        service.reconcile();
        assertTrue(registry.usages(project.workspaceId()).isEmpty());
        verify(jobs, times(2)).valid(any(), any(), eq(false));
    }
}
