package com.stonewu.agenteam.mapper.skill;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

/**
 * 同名提示只检查本人的草稿，不能通过导入探测他人的资源名称。
 */
@Repository
public class SkillNameMapper {
    private final SkillNameSqlMapper statements;

    public SkillNameMapper(SkillNameSqlMapper statements) {
        this.statements = statements;
    }

    public boolean ownNameExists(String enterprise, String user, String name) {
        return DataAccessUtils.nullableSingleResult(statements.ownNameExistsResource(enterprise, user, name)) > 0;
    }
}
