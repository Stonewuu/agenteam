package com.stonewu.agenteam.model.workspace.response;

import java.util.List;

/**
 * 本人在当前企业过去一年的活动；所有日期均按企业时区计算。
 */
public record ActivityTrendView(String startDate, String endDate, String timezone, List<Day> days) {
    public record Day(String date, long conversations, long schedules, long todos, long employees) {
        public long total() {
            return conversations + schedules + todos + employees;
        }
    }
}
