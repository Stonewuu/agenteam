package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** 每十秒续约，网络连接在独立虚拟线程建立，失去租约立即停止接收和发送。 */
@Component
public class ChatConnectionManager {
    private static final Logger LOG = LoggerFactory.getLogger(ChatConnectionManager.class);
    private static final class Slot {
        private final ChatConnectionTransactions.Lease lease;
        private final AtomicBoolean working = new AtomicBoolean();
        private volatile ChatConnectionRegistry.Owned owned;
        private volatile Instant retryAt = Instant.MIN;
        private volatile int failures;
        private volatile boolean closed;
        private Slot(ChatConnectionTransactions.Lease lease) {
            this.lease = lease;
        }
    }
    private final String owner = UUID.randomUUID().toString();
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ChatConnectionProvider> providers;
    private final ChatConnectionTransactions transactions;
    private final ChatConnectionRegistry registry;
    private final ObjectProvider<ChatIngressService> ingress;
    private final ObjectProvider<ChatCardInteractionService> cards;
    private final IntegrationSecretService secrets;
    private final ChatConfiguration configuration;
    private final Clock clock;
    private volatile boolean closed;

    public ChatConnectionManager(List<ChatConnectionProvider> providers, ChatConnectionTransactions transactions,
            ChatConnectionRegistry registry, ObjectProvider<ChatIngressService> ingress, IntegrationSecretService secrets,
            ChatConfiguration configuration, Clock clock, ObjectProvider<ChatCardInteractionService> cards) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(ChatConnectionProvider::providerCode, value -> value));
        this.transactions = transactions;
        this.registry = registry;
        this.ingress = ingress;
        this.cards = cards;
        this.secrets = secrets;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Scheduled(scheduler = "chatConnectionScheduler", fixedDelayString = "${chat.connection-poll-interval-ms:10000}")
    public synchronized void poll() {
        if (closed) {
            return;
        }
        for (Slot slot : List.copyOf(slots.values())) {
            try {
                if (!configuration.isWorkerEnabled() || !transactions.renew(slot.lease)
                    || slot.owned != null && slot.owned.connection().replaced()) {
                    stop(slot, slot.owned != null && slot.owned.connection().replaced());
                } else {
                    advance(slot);
                }
            } catch (RuntimeException failure) {
                LOG.warn("机器人连接续约失败，机器人={}", slot.lease.botId(), ChatSocket.safe("CHAT_LEASE_FAILED", "旧连接停止工作。", failure));
                stop(slot, false);
            }
        }
        if (!configuration.isEnabled() || !configuration.isWorkerEnabled()) {
            return;
        }
        try {
            for (var bot : transactions.candidates()) {
                if (slots.size() >= 100) {
                    break;
                }
                if (!slots.containsKey(bot.getId())) {
                    transactions.claim(bot.getEnterpriseId(), bot.getId(), owner).ifPresent(lease -> {
                        var slot = new Slot(lease);
                        slots.put(lease.botId(), slot);
                        advance(slot);
                    });
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("机器人连接领取失败", ChatSocket.safe("CHAT_CONNECT_POLL_FAILED", "稍后再次领取连接。", failure));
        }
    }

    private void advance(Slot slot) {
        if (slot.retryAt.isAfter(clock.instant()) || !slot.working.compareAndSet(false, true)) {
            return;
        }
        executor.submit(() -> {
            try {
                if (slot.closed) {
                    return;
                }
                var active = slot.owned;
                if (active != null && active.connection().replaced()) {
                    stop(slot, true);
                    return;
                }
                if (active != null && active.connection().ready()) {
                    transactions.require(slot.lease);
                    active.connection().tick(clock.instant());
                    return;
                }
                disconnect(slot);
                transactions.require(slot.lease);
                var lease = slot.lease;
                var provider = providers.get(lease.application().providerCode());
                if (provider == null) {
                    throw ChatPlatformRegistry.unavailable();
                }
                var connection = provider.openInteractive(lease.application(), secrets.load(lease.enterprise(), lease.application().id()), event -> {
                    transactions.require(lease);
                    if (ChatCardInteractionService.isCard(event)) {
                        return cards.getObject().accept(lease.enterprise(), lease.botId(), lease.version(),
                            lease.application().credentialRevision(), lease.configurationRevision(), event);
                    }
                    ingress.getObject().acceptVerified(lease.enterprise(), lease.botId(), lease.version(),
                        lease.application().credentialRevision(), lease.configurationRevision(), "websocket", event);
                    return null;
                });
                try {
                    transactions.require(lease);
                    synchronized (slot) {
                        if (slot.closed) {
                            connection.close();
                            return;
                        }
                        slot.owned = new ChatConnectionRegistry.Owned(lease.application(), lease.botId(), lease.version(), connection,
                            () -> transactions.require(lease));
                        registry.put(slot.owned);
                    }
                    transactions.connected(lease);
                    slot.failures = 0;
                } catch (RuntimeException failure) {
                    connection.close();
                    throw failure;
                }
            } catch (RuntimeException failure) {
                disconnect(slot);
                slot.retryAt = clock.instant().plusSeconds(Math.min(60, 1L << Math.min(6, ++slot.failures)) + ThreadLocalRandom.current().nextLong(1, 5));
                LOG.warn("机器人连接暂时不可用，机器人={}", slot.lease.botId(), ChatSocket.safe("CHAT_CONNECT_RETRY", "连接将在退避后重试。", failure));
            } finally {
                slot.working.set(false);
            }
        });
    }

    private void disconnect(Slot slot) {
        synchronized (slot) {
            var owned = slot.owned;
            slot.owned = null;
            if (owned != null) {
                registry.remove(owned);
                owned.connection().close();
            }
        }
    }

    private void stop(Slot slot, boolean replaced) {
        slot.closed = true;
        disconnect(slot);
        slots.remove(slot.lease.botId(), slot);
        try {
            transactions.release(slot.lease, replaced);
        } catch (RuntimeException failure) {
            LOG.warn("机器人连接释放失败，机器人={}", slot.lease.botId(), ChatSocket.safe("CHAT_RELEASE_FAILED", "连接租约到期后由其他进程处理。", failure));
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        List.copyOf(slots.values()).forEach(slot -> stop(slot, false));
        executor.shutdownNow();
    }
}
