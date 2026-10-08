package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.enterprise.response.EnterpriseContextResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.edition.ClientCapabilityService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 企业权限和权限版本在同一次受保护的读取中返回，避免混合修改前后的状态。
 */
@Service
public class EnterpriseContextService {
    private final EnterpriseMapper enterprises;
    private final IdentityViewMapper views;
    private final AuthContextService context;
    private final ClientCapabilityService capabilities;

    public EnterpriseContextService(EnterpriseMapper enterprises, IdentityViewMapper views,
                                    AuthContextService context, ClientCapabilityService capabilities) {
        this.enterprises = enterprises;
        this.views = views;
        this.context = context;
        this.capabilities = capabilities;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EnterpriseContextResponse get(HttpSession session, String enterpriseId) {
        context.requireEnterprise(session, enterpriseId);
        enterprises.lockEnterprise(enterpriseId);
        var actor = context.requireEnterprise(session, enterpriseId);
        var menus = new ArrayList<EnterpriseContextResponse.Menu>();
        String base = "/e/" + UriUtils.encodePathSegment(enterpriseId, StandardCharsets.UTF_8);
        if (actor.permissions().contains("workspace.view")) {
            menus.add(new EnterpriseContextResponse.Menu("workspace", "工作台", "user", base));
        }
        if (actor.permissions().contains("admin.view")) {
            menus.add(new EnterpriseContextResponse.Menu("admin", "企业管理", "admin", base + "/admin"));
        }
        return new EnterpriseContextResponse(views.enterprise(enterpriseId).orElseThrow(),
            views.member(enterpriseId, actor.userId()),
            actor.permissions().stream().sorted().toList(), views.permissionVersion(enterpriseId), menus,
            capabilities.enterprise(actor));
    }
}
