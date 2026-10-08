package com.stonewu.agenteam.controller.notification;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.request.IntegrationTestMessageRequest;
import com.stonewu.agenteam.model.notification.request.ChannelDeliveryRetryRequest;
import com.stonewu.agenteam.model.notification.response.ChannelDeliveryDetail;
import com.stonewu.agenteam.model.notification.response.ChannelDeliveryView;
import com.stonewu.agenteam.model.notification.response.NotificationRecipientOption;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.notification.ChannelDeliveryApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理发送记录与测试消息，业务服务分别检查成员权限或全局管理身份。 */
@RestController
@RequestMapping({"/api/v1/enterprises/{enterpriseId}", "/api/v1/system/enterprises/{enterpriseId}"})
public class ChannelDeliveryApiController {
    private final ChannelDeliveryApiService deliveries;
    private final ApiResponses responses;

    public ChannelDeliveryApiController(ChannelDeliveryApiService deliveries, ApiResponses responses) {
        this.deliveries = deliveries;
        this.responses = responses;
    }

    @GetMapping("/integrations/{connectionId}/deliveries")
    public ApiResponse<PageResponse<ChannelDeliveryView>> list(@PathVariable String enterpriseId, @PathVariable String connectionId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(deliveries.list(enterpriseId, connectionId, cursor, limit, request), request);
    }

    @GetMapping("/notification-deliveries/{deliveryId}")
    public ApiResponse<ChannelDeliveryDetail> detail(@PathVariable String enterpriseId, @PathVariable String deliveryId, HttpServletRequest request) {
        return responses.success(deliveries.detail(enterpriseId, deliveryId, request), request);
    }

    @GetMapping("/integrations/{connectionId}/recipients")
    public ApiResponse<PageResponse<NotificationRecipientOption>> recipients(@PathVariable String enterpriseId, @PathVariable String connectionId,
            @RequestParam(required = false) String search, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(deliveries.recipients(enterpriseId, connectionId, search, cursor, limit, request), request);
    }

    @PostMapping("/notification-deliveries/{deliveryId}/retry")
    public ResponseEntity<ApiResponse<Object>> retry(@PathVariable String enterpriseId, @PathVariable String deliveryId,
            @RequestBody ChannelDeliveryRetryRequest input, HttpServletRequest request) {
        return responses.operation(deliveries.retry(enterpriseId, deliveryId, input, request), request);
    }

    @PostMapping("/integrations/{connectionId}/test-messages")
    public ResponseEntity<ApiResponse<Object>> test(@PathVariable String enterpriseId, @PathVariable String connectionId,
            @RequestBody IntegrationTestMessageRequest input, HttpServletRequest request) {
        return responses.operation(deliveries.test(enterpriseId, connectionId, input, request), request);
    }
}
