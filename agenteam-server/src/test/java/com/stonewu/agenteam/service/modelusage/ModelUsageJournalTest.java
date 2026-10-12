package com.stonewu.agenteam.service.modelusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageJournalTest {
    private final ModelUsageService service = mock(ModelUsageService.class);
    private final ModelUsageJournal journal = new ModelUsageJournal(service, Clock.systemUTC(), 2);
    private final ModelCallContext context = new ModelCallContext("enterprise", "user", "conversation", "run", 1, 4,
        "agent", "session", null, "conversation", "conversation", "agent");

    @Test
    void failedFinalWritesRetainBoundedFactsAndNeverRepeatTheModelRegistration() {
        var failing = new AtomicBoolean(true);
        when(service.save(any())).thenAnswer(invocation -> {
            if (failing.get()) {
                throw new DataAccessResourceFailureException("模拟数据库不可用");
            }
            return true;
        });
        var first = open();
        var second = open();
        first.beforeDispatch();
        second.beforeDispatch();
        first.finish("completed");
        first.finish("completed");
        second.finish("cancelled");
        assertThatThrownBy(this::open).isInstanceOf(IllegalStateException.class).hasMessageContaining("正在恢复");
        failing.set(false);
        journal.flush();
        verify(service, times(2)).register(anyString(), anyString(), any(), any(), any());
        verify(service, times(4)).save(any());
        open().finish("cancelled");
        open().finish("cancelled");
    }

    @Test
    void cancellationWaitsForRegistrationAndSavesExactlyOneTerminalSnapshot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(service).register(anyString(), anyString(), any(), any(), any());
        var call = open();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var registration = workers.submit(call::beforeDispatch);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var finished = workers.submit(() -> call.finish("cancelled"));
            assertThat(finished.isDone()).isFalse();
            release.countDown();
            registration.get(3, TimeUnit.SECONDS);
            finished.get(3, TimeUnit.SECONDS);
        }
        var saved = ArgumentCaptor.forClass(ModelUsageSnapshot.class);
        verify(service).save(saved.capture());
        assertThat(saved.getValue().callStatus()).isEqualTo("cancelled");
        assertThat(saved.getValue().sequence()).isEqualTo(1);
        journal.flush();
        verify(service, times(1)).save(any());
    }

    @Test
    void cancellationBeforeDispatchDoesNotRegisterAndReleasesItsSlot() {
        var call = open();
        call.finish("cancelled");
        assertThatThrownBy(call::beforeDispatch).isInstanceOf(CancellationException.class);
        verify(service, never()).register(anyString(), anyString(), any(), any(), any());
        open().finish("cancelled");
        open().finish("cancelled");
    }

    @Test
    void checkpointsUseLatestCumulativeValuesAndHeartbeatEvenWhenNoTokensArrive() throws Exception {
        var call = open();
        call.beforeDispatch();
        journal.flush();
        verify(service).heartbeat(eq("enterprise"), eq(call.id()), anyString());
        var json = new ObjectMapper();
        call.usage().accept(json.readTree("{\"completion_tokens\":10}"));
        call.usage().accept(json.readTree("{\"completion_tokens\":50}"));
        journal.flush();
        call.usage().finish();
        call.finish("completed");
        var saved = ArgumentCaptor.forClass(ModelUsageSnapshot.class);
        verify(service, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allMatch(value -> value.usage().usage().outputTokens() == 50);
        assertThat(saved.getAllValues().getFirst().sequence()).isLessThan(saved.getAllValues().getLast().sequence());
    }

    private ModelUsageJournal.Call open() {
        var now = Instant.now();
        var profile = new ModelProfileRecord("model", "enterprise", "provider", "测试模型", "test-model",
            new ModelCapabilities(true, true, 1024, 8000, List.of("text")), true, 1, "测试连接", "openai",
            "https://example.test/v1", true, now, now);
        var provider = new ModelProviderRecord("provider", "enterprise", "测试连接", "openai", profile.baseUrl(), "test-only", true, 1, now, now);
        return journal.open(context, profile, provider);
    }
}
