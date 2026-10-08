package com.stonewu.agenteam.mapper.test.execution;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.RunStepRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * run_step 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface RunStepFixtureMapper extends MPJBaseMapper<RunStepRow> {
    List<String> workflowExecutionApiEntryWorkflowPersistsActualMappedResultWithoutCallingAModelObject(@Param("args") Object... args);

    List<Integer> workflowExecutionApiDirectReadNodeUsesTheActualPublishedToolAndItsExistingCallRecordObject(@Param("args") Object... args);

    List<String> workflowPreviewApiInvalidFinalReferencesAndOversizedTextFailTheEndNodeBeforePublishingAnyResultObject(@Param("args") Object... args);

}
