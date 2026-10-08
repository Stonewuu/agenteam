package com.stonewu.agenteam.model.enterprise.response;

import java.util.List;

/**
 * 新版成员结构；版本使用字符串避免浏览器整数精度损失。
 */
public record MemberView(String userId, String displayName, String email, String status, List<String> teamIds,
                         List<String> roleIds, String joinedAt, String revision) {
}
