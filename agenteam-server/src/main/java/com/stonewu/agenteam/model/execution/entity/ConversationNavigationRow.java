package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 列表先按协作任务合并，仍保留实际打开的岗位对话。 */
@Getter
@Setter
public class ConversationNavigationRow extends ConversationQueryRow {
    private String entryKey;
    private String taskId;
    private Instant groupUpdatedAt;
    private Boolean groupFavorite;
}
