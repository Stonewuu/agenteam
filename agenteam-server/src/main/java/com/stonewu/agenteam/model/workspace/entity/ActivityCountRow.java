package com.stonewu.agenteam.model.workspace.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 查询一天内同一类用户活动的次数，不读取对话正文或操作详情。
 */
@Getter
@Setter
public class ActivityCountRow {
    private String date;
    private String category;
    private long count;
}
