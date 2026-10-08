package com.stonewu.agenteam.service.permission;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.resource.entity.ResourceSubjectRecord;

import java.util.List;

/** 为公共企业/用户授权补充其他对象类型，不替代资源管理资格与事务校验。 */
public interface ResourceGrantExtension {
    String subjectType();

    void validateSubject(AuthContext actor, ResourceGrantSpec grant);

    void authorizeChange(AuthContext actor, List<ResourceGrantSpec> before, List<ResourceGrantSpec> after);

    List<ResourceSubjectRecord> subjects(AuthContext actor, String query, List<String> selected,
                                          PagePosition cursor, int limit);
}
