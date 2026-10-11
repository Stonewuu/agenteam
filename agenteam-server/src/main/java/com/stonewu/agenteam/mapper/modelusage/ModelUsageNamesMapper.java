package com.stonewu.agenteam.mapper.modelusage;

import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageNamesRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 从已获准读取的本页调用批量查询名称，模型关联必须仍属于当前企业。 */
@Repository
public class ModelUsageNamesMapper {
    private final ModelUsageRecordMapper rows;

    public ModelUsageNamesMapper(ModelUsageRecordMapper rows) {
        this.rows = rows;
    }

    public Map<String, ModelUsageNamesRow> names(String enterprise, List<ModelUsageRecordRow> calls) {
        if (calls.isEmpty()) {
            return Map.of();
        }
        var query = JoinWrappers.lambda(ModelUsageRecordRow.class)
            .selectAs(ModelUsageRecordRow::getId, ModelUsageNamesRow::getId)
            .selectAs(AppUserRow::getDisplayName, ModelUsageNamesRow::getUserName)
            .selectAs(ModelProfileRow::getName, ModelUsageNamesRow::getModelProfileName)
            .leftJoin(AppUserRow.class, AppUserRow::getId, ModelUsageRecordRow::getUserId)
            .leftJoin(ModelProfileRow.class, on -> on.eq(ModelProfileRow::getId, ModelUsageRecordRow::getModelProfileId)
                .eq(ModelProfileRow::getEnterpriseId, ModelUsageRecordRow::getEnterpriseId))
            .eq(ModelUsageRecordRow::getEnterpriseId, enterprise)
            .in(ModelUsageRecordRow::getId, calls.stream().map(ModelUsageRecordRow::getId).toList());
        return rows.selectJoinList(ModelUsageNamesRow.class, query).stream()
            .collect(Collectors.toMap(ModelUsageNamesRow::getId, Function.identity()));
    }
}
