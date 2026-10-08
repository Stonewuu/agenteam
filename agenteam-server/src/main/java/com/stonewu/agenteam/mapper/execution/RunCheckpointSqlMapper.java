package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.RunCheckpointQueryRow;
import com.stonewu.agenteam.model.execution.entity.RunCheckpointRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * RunCheckpointMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface RunCheckpointSqlMapper extends MPJBaseMapper<RunCheckpointRow> {
    default int removeRunCheckpoint(String enterprise, String run) {
        return delete(new LambdaQueryWrapper<RunCheckpointRow>().eq(RunCheckpointRow::getEnterpriseId, enterprise)
            .eq(RunCheckpointRow::getRunId, run));
    }

    default List<RunCheckpointQueryRow> findRunCheckpoint(String enterprise, String run) {
        var criteria = new LambdaQueryWrapper<RunCheckpointRow>().eq(RunCheckpointRow::getEnterpriseId, enterprise)
            .eq(RunCheckpointRow::getRunId, run);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunCheckpointQueryRow();
            if (storedRow.getLeaseVersion() != null) {
                mappedRow.setLeaseVersion(storedRow.getLeaseVersion());
            }
            if (storedRow.getStateJson() != null) {
                mappedRow.setStateJson(storedRow.getStateJson());
            }
            if (storedRow.getFrameworkStateKey() != null) {
                mappedRow.setFrameworkStateKey(storedRow.getFrameworkStateKey());
            }
            if (storedRow.getStateHash() != null) {
                mappedRow.setStateHash(storedRow.getStateHash());
            }
            return mappedRow;
        }).toList();
    }

    int saveRunCheckpoint(@Param("enterpriseId") String enterpriseId, @Param("runId") String runId,
                          @Param("version") long version, @Param("value") String value,
                          @Param("stateKey") String stateKey, @Param("value2") String value2,
                          @Param("now") Timestamp now);

    default int deleteRunCheckpoints(String enterpriseId, String id) {
        return delete(new LambdaQueryWrapper<RunCheckpointRow>().eq(RunCheckpointRow::getEnterpriseId, enterpriseId)
            .eq(RunCheckpointRow::getRunId, id));
    }
}
