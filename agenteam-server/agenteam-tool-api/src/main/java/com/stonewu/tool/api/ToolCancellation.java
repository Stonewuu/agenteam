package com.stonewu.tool.api;

/**
 * 调用共享的取消信号与资源范围；取消不会自动撤回已发送的外部操作。
 */
public interface ToolCancellation {

    void requireActive();

    <T extends AutoCloseable> T track(T resource);
}
