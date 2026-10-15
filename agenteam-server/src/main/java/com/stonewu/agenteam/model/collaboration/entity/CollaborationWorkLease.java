package com.stonewu.agenteam.model.collaboration.entity;

/** 工作领取版本，仅由内部调度程序构造。 */
public record CollaborationWorkLease(String enterpriseId, String workId, long version, String owner) {
}
