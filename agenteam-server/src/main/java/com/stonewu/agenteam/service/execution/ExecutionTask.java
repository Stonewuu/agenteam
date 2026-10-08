package com.stonewu.agenteam.service.execution;

import reactor.core.publisher.Mono;

/**
 * 工作进程统一处理模型对话和工作流；等待确认时保存进度并释放线程。
 */
public interface ExecutionTask extends AutoCloseable {
    Mono<Void> completion();

    void cancel();

    boolean cancelled();

    boolean waiting();

    void saveCheckpoint();

    @Override
    void close();
}
