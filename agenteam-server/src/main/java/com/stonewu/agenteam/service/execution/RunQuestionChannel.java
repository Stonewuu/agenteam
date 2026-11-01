package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunQuestionView;

/** 渠道只负责问题的展示与通知，回答和执行恢复共用问题服务。 */
public interface RunQuestionChannel {
    void initialize(RunRecord run, RunQuestionRow question);

    void created(RunRecord run, RunQuestionRow question, RunQuestionView view);

    default void answered(RunRecord run, RunQuestionRow question, RunQuestionView view) {
    }

    default void resumed(RunRecord run) {
    }
}
