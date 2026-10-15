package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollaborationDependencyTest {
    @Test
    void twoParallelJobsCanJoinWithoutRequiringEachOther() {
        assertDoesNotThrow(() -> CollaborationDependencyService.requireAcyclic(Map.of("汇总", Set.of("考勤", "工资"))));
    }

    @Test
    void rejectsSelfDependencyAndIndirectCycles() {
        assertEquals("WORK_DEPENDENCY_CYCLE", assertThrows(ApiException.class,
            () -> CollaborationDependencyService.requireAcyclic(Map.of("人事", Set.of("人事")))).code());
        assertThrows(ApiException.class, () -> CollaborationDependencyService.requireAcyclic(Map.of(
            "人事", Set.of("财务"), "财务", Set.of("行政"), "行政", Set.of("人事"))));
    }

    @Test
    void disconnectedCycleCannotHideBehindCompletedIndependentBranch() {
        assertThrows(ApiException.class, () -> CollaborationDependencyService.requireAcyclic(Map.of(
            "汇总", Set.of("独立成果"), "人事", Set.of("财务"), "财务", Set.of("人事"))));
    }
}
