package com.stonewu.agenteam.model.resource.response;

/**
 * 授权与转交选项仅返回对象名称和当前可选状态，不包含成员联系方式或角色明细。
 */
public record ResourceSubjectOption(String id, String name, String subjectType, boolean active) {
}
