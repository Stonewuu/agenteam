package com.stonewu.agenteam.model.resource.entity;

import com.stonewu.agenteam.model.resource.response.ResourceSubjectOption;

import java.time.Instant;

/**
 * 授权对象的公开选项及服务端分页位置。
 */
public record ResourceSubjectRecord(ResourceSubjectOption option, Instant createdAt) {
}
