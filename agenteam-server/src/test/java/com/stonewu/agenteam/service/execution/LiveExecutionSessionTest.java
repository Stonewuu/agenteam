package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LiveExecutionSessionTest {
    @Test
    void closingAfterSaveFailurePreservesTheOriginalExceptionAndReleasesTheSession() {
        var delivery = mock(LiveEventDelivery.class);
        var lease = mock(JobLease.class);
        var failure = new IllegalStateException("test persistence failure");
        var thrown = assertThrows(IllegalStateException.class, () -> {
            try (var session = new LiveExecutionSession(delivery, mock(RunRecord.class), lease, List.of(), Clock.systemUTC())) {
                session.persist(List.of(), (batch, changes) -> {
                    throw failure;
                }, true);
                session.drain();
            }
        });
        assertSame(failure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(failure, thrown.getSuppressed()[0].getCause());
        verify(delivery).closed(eq(lease), any());
    }
}
