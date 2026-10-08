package com.stonewu.agenteam.mapper.schedule;

import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduleChannelOptionRow;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 先分页取得成员，再批量读取其可发送渠道。 */
@Repository
public class ScheduleOptionMapper {
    private final UserChannelBindingMapper bindings;

    public ScheduleOptionMapper(UserChannelBindingMapper bindings) {
        this.bindings = bindings;
    }

    public List<ScheduleChannelOptionRow> channels(String enterprise, List<String> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        var query = JoinWrappers.lambda(UserChannelBindingRow.class)
            .selectAs(UserChannelBindingRow::getUserId, ScheduleChannelOptionRow::getUserId)
            .selectAs(UserChannelBindingRow::getConnectionId, ScheduleChannelOptionRow::getConnectionId)
            .selectAs(EnterpriseIntegrationRow::getName, ScheduleChannelOptionRow::getName)
            .selectAs(EnterpriseIntegrationRow::getProviderCode, ScheduleChannelOptionRow::getProviderCode)
            .innerJoin(EnterpriseIntegrationRow.class, on -> on.eq(EnterpriseIntegrationRow::getEnterpriseId, UserChannelBindingRow::getEnterpriseId)
                .eq(EnterpriseIntegrationRow::getId, UserChannelBindingRow::getConnectionId))
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).in(UserChannelBindingRow::getUserId, users)
            .eq(UserChannelBindingRow::getStatus, "active").eq(UserChannelBindingRow::getReceiveEnabled, true)
            .eq(EnterpriseIntegrationRow::getStatus, "enabled").eq(EnterpriseIntegrationRow::getMessagingEnabled, true)
            .orderByAsc(EnterpriseIntegrationRow::getName, EnterpriseIntegrationRow::getId);
        return bindings.selectJoinList(ScheduleChannelOptionRow.class, query);
    }
}
