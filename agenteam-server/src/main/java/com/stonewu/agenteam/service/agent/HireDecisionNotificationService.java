package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.model.agent.entity.AgentHireApplication;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 雇佣决定只提醒申请人，处理说明留在按当前权限读取的申请详情中。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class HireDecisionNotificationService {
    private final NotificationDeliveryService notifications;

    public HireDecisionNotificationService(NotificationDeliveryService notifications) {
        this.notifications = notifications;
    }

    public void decided(AgentHireApplication application, boolean approved) {
        notifications.enqueue(application.enterpriseId(), application.userId(),
            new Notice("hire-request:" + application.id() + ":decision", "hire",
                "雇佣申请" + (approved ? "已批准" : "已拒绝"),
                "“" + application.agentName() + "”的雇佣申请已处理，请查看申请详情。", "hire_request", application.id()));
    }
}
