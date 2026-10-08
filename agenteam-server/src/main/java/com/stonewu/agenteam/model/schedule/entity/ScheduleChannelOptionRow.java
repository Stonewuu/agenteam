package com.stonewu.agenteam.model.schedule.entity;

import lombok.Getter;
import lombok.Setter;

/** 成员和企业接入关联查询的结果，不包含外部账号及秘密。 */
@Getter
@Setter
public class ScheduleChannelOptionRow {
    private String userId;
    private String connectionId;
    private String name;
    private String providerCode;
}
