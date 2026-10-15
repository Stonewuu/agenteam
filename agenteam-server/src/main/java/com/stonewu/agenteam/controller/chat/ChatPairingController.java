package com.stonewu.agenteam.controller.chat;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.chat.ChatPairingService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 一次性配对码只在此次响应出现，不保存到普通重复请求响应记录。 */
@RestController
public class ChatPairingController {
    public record PairingRequest(@NotBlank @Size(max = 100) String connectionId) {
    }
    private final ChatPairingService pairing;
    private final ApiResponses responses;
    public ChatPairingController(ChatPairingService pairing, ApiResponses responses) {
        this.pairing = pairing;
        this.responses = responses;
    }

    @PostMapping("/api/v1/enterprises/{enterpriseId}/chat/bindings/pairing")
    public ApiResponse<Map<String, String>> create(@PathVariable String enterpriseId, @RequestBody PairingRequest input, HttpServletRequest request) {
        InputValidation.request(request, PairingRequest.class);
        return responses.success(pairing.create(enterpriseId, input.connectionId(), request.getSession(false)), request);
    }
}
