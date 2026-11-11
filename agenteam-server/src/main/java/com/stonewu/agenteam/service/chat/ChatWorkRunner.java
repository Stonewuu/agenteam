package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 合并连续唤醒；每类工作只运行一份，不在提交事务的线程中处理消息。 */
final class ChatWorkRunner implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChatWorkRunner.class);
    private final String name;
    private final BooleanSupplier batch;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor executor;

    ChatWorkRunner(String name, BooleanSupplier batch) {
        this.name = name;
        this.batch = batch;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            Thread.ofVirtual().name("chat-" + name + "-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    void wake() {
        if (closed.get()) {
            return;
        }
        requested.set(true);
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException rejected) {
            running.set(false);
            LOG.debug(Messages.log("log.chat.chatWorkRunner.wake.1"), name);
        }
    }

    private void drain() {
        try {
            do {
                requested.set(false);
                if (closed.get()) {
                    return;
                }
                if (batch.getAsBoolean()) {
                    requested.set(true);
                }
            } while (requested.get() && !closed.get());
        } catch (RuntimeException failure) {
            if (!closed.get()) {
                LOG.warn(Messages.log("log.chat.chatWorkRunner.drain.1"), name, ChatSocket.safe("CHAT_WORK_FAILED", Messages.text("text.chat.chatWorkRunner.drain.1"), failure));
            }
        } finally {
            running.set(false);
            // 检查与退出之间收到的通知也要处理，不能等下一次定时扫描。
            if (requested.get() && !closed.get()) {
                wake();
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        executor.shutdownNow();
    }
}
