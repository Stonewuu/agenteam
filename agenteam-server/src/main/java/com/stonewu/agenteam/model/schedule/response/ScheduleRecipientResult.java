package com.stonewu.agenteam.model.schedule.response;

import com.stonewu.agenteam.model.notification.response.ChannelDeliveryView;
import java.util.List;

/** 本次固定接收人的站内结果和逐渠道真实发送记录，不包含外部账号。 */
public record ScheduleRecipientResult(String userId, String name, String inAppStatus, String reason,
                                       List<ChannelDeliveryView> channels) {
}
