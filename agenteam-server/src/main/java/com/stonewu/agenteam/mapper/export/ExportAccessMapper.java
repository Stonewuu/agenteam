package com.stonewu.agenteam.mapper.export;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;

/**
 * 文件已经生成后仍检查其中全部操作者是否属于当前允许范围，不能只检查操作权限还在。
 */
@Repository
public class ExportAccessMapper {
    private final ExportAccessSqlMapper statements;

    public ExportAccessMapper(ExportAccessSqlMapper statements) {
        this.statements = statements;
    }

    public boolean includesActors(AuthContext actor, DataScope scope, List<String> actors) {
        if (scope == DataScope.ENTERPRISE || actors.isEmpty()) {
            return true;
        }
        if (scope == DataScope.OWN) {
            return actors.stream().allMatch(actor.userId()::equals);
        }
        var allowed = new OwnerQueryScope(actor.enterpriseId(), actor.userId(), scope.code());
        return new HashSet<>(statements.visibleActors(allowed, actors)).containsAll(actors);
    }
}
