package com.stonewu.agenteam.model.security.entity;

/**
 * 密文记录仅供服务器调用适配器使用，没有公开读取秘密的接口。
 */
public record CredentialRecord(String id, String enterpriseId, String kind, String status, EncryptedPayload payload) {
}
