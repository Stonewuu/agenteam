package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.RunStepQueryRow;
import com.stonewu.agenteam.model.execution.entity.RunStepRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * RunStepMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface RunStepSqlMapper extends MPJBaseMapper<RunStepRow> {


    int saveRunStep(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("id2") String id2,
                    @Param("attemptId") String attemptId, @Param("parentStepId") String parentStepId,
                    @Param("kind") String kind, @Param("title") String title, @Param("displayOrder") int displayOrder,
                    @Param("status") String status, @Param("value") String value, @Param("value2") String value2,
                    @Param("publicSummary") String publicSummary, @Param("value3") String value3,
                    @Param("leaseVersion") long leaseVersion, @Param("value4") Timestamp value4,
                    @Param("value5") Timestamp value5, @Param("now") Timestamp now, @Param("value6") boolean value6);

    default List<RunStepQueryRow> listRunStep(String enterprise, String run) {
        var criteria = new LambdaQueryWrapper<RunStepRow>().orderByAsc(RunStepRow::getDisplayOrder)
            .orderByAsc(RunStepRow::getId).eq(RunStepRow::getEnterpriseId, enterprise).eq(RunStepRow::getRunId, run);
        return selectList(criteria).stream().map(RunStepSqlMapper::stepRow).toList();
    }

    default List<String> inputRunStep(String enterprise, String run, String step) {
        var criteria = new LambdaQueryWrapper<RunStepRow>().select(RunStepRow::getInputJson)
            .eq(RunStepRow::getEnterpriseId, enterprise).eq(RunStepRow::getRunId, run).eq(RunStepRow::getId, step);
        return selectList(criteria).stream().map(storedRow -> storedRow.getInputJson()).toList();
    }

    default List<RunStepQueryRow> pageRunStep(String enterprise, String run, int afterOrder, String afterId,
                                              int limit) {
        var query = new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getEnterpriseId, enterprise)
            .eq(RunStepRow::getRunId, run)
            .and(part -> part.gt(RunStepRow::getDisplayOrder, afterOrder)
                .or(equal -> equal.eq(RunStepRow::getDisplayOrder, afterOrder).gt(RunStepRow::getId, afterId)))
            .orderByAsc(RunStepRow::getDisplayOrder, RunStepRow::getId);
        return selectPage(new Page<RunStepRow>(1, limit, false), query).getRecords().stream()
            .map(RunStepSqlMapper::stepRow).toList();
    }

    default int removeRunStep(String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<RunStepRow>().eq(RunStepRow::getEnterpriseId, enterpriseId)
            .eq(RunStepRow::getRunId, id).set(RunStepRow::getParentStepId, null));
    }

    default int deleteRunSteps(String enterpriseId, String id) {
        return delete(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getEnterpriseId, enterpriseId)
            .eq(RunStepRow::getRunId, id));
    }

    private static RunStepQueryRow stepRow(RunStepRow storedRow) {
        var mappedRow = new RunStepQueryRow();
        if (storedRow.getId() != null) {
            mappedRow.setId(storedRow.getId());
        }
        if (storedRow.getAttemptId() != null) {
            mappedRow.setAttemptId(storedRow.getAttemptId());
        }
        if (storedRow.getParentStepId() != null) {
            mappedRow.setParentStepId(storedRow.getParentStepId());
        }
        if (storedRow.getKind() != null) {
            mappedRow.setKind(storedRow.getKind());
        }
        if (storedRow.getTitle() != null) {
            mappedRow.setTitle(storedRow.getTitle());
        }
        if (storedRow.getDisplayOrder() != null) {
            mappedRow.setDisplayOrder(
                (storedRow.getDisplayOrder() == null ? null : Math.toIntExact(storedRow.getDisplayOrder())));
        }
        if (storedRow.getStatus() != null) {
            mappedRow.setStatus(storedRow.getStatus());
        }
        if (storedRow.getPublicSummary() != null) {
            mappedRow.setPublicSummary(storedRow.getPublicSummary());
        }
        if (storedRow.getWorkflowJson() != null) {
            mappedRow.setWorkflowJson(storedRow.getWorkflowJson());
        }
        if (storedRow.getStartedAt() != null) {
            mappedRow.setStartedAt(
                (storedRow.getStartedAt() == null ? null : Timestamp.from(storedRow.getStartedAt())));
        }
        if (storedRow.getFinishedAt() != null) {
            mappedRow.setFinishedAt(
                (storedRow.getFinishedAt() == null ? null : Timestamp.from(storedRow.getFinishedAt())));
        }
        return mappedRow;

    }
}
