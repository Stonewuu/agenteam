package com.stonewu.agenteam.model.environment.entity;

/** 仅保存在容器不可写的控制目录，修改时必须持有用户运行锁。 */
public class EnvironmentTerminalState {
    public String id;
    public String status;
    public String containerId;
    public String generation;
    public long clientAt;
    public long workerAt;
    public long sequence;
    public String inputHash = "";
    public String pendingInput = "";
    public int cols;
    public int rows;
    public long outputStart;
    public long outputEnd;
    public String directory = "/workspace";
    public boolean busy;
    public String error;

    public boolean active() {
        return "starting".equals(status) || "running".equals(status) || "closing".equals(status);
    }

    public boolean protects(long now) {
        return active() && (busy || "starting".equals(status) || "closing".equals(status))
            && now - workerAt < 60000 && now - clientAt < 60000;
    }
}
