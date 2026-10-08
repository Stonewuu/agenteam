package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.edition.ClientCapabilityService;
import org.springframework.stereotype.Service;

/** 登录、个人资料和接受邀请使用同一套身份与可用操作响应。 */
@Service
public class IdentityResponseService {
    private final IdentityViewMapper views;
    private final ClientCapabilityService capabilities;

    public IdentityResponseService(IdentityViewMapper views, ClientCapabilityService capabilities) {
        this.views = views;
        this.capabilities = capabilities;
    }

    public CurrentIdentityResponse current(UserEntity user) {
        return views.current(user, capabilities.global(user));
    }
}
