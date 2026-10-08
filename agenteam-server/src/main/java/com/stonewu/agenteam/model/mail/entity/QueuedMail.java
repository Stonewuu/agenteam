package com.stonewu.agenteam.model.mail.entity;

/**
 * 取回先前投递内容时保留原工作编号，用于验证加密内容归属。
 */
public record QueuedMail(String id, String payloadJson) {
    @Override
    public String toString() {
        return "QueuedMail[id=" + id + "]";
    }
}
