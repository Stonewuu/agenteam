package com.stonewu.agenteam.service.edition;

import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 在身份和企业权限已经核对后，汇总当前账号可以使用的页面操作。 */
@Service
public class ClientCapabilityService {
    private final List<ClientCapabilityProvider> providers;
    private final AccountBehaviorService behavior;

    public ClientCapabilityService(List<ClientCapabilityProvider> providers, AccountBehaviorService behavior) {
        this.providers = List.copyOf(providers);
        this.behavior = behavior;
    }

    public List<String> global(UserEntity user) {
        var result = new ArrayList<String>();
        if (behavior.operationAllowed(user.id(), AccountOperation.CHANGE_PASSWORD)) {
            result.add("account.password.change");
        }
        if (behavior.operationAllowed(user.id(), AccountOperation.CHANGE_EMAIL)) {
            result.add("account.email.change");
        }
        if (behavior.operationAllowed(user.id(), AccountOperation.BIND_EXTERNAL_ACCOUNT)) {
            result.add("account.external.bind");
        }
        providers.forEach(provider -> result.addAll(provider.global(user)));
        return result.stream().distinct().sorted().toList();
    }

    public List<String> enterprise(AuthContext actor) {
        var result = new ArrayList<String>();
        if (actor.permissions().containsAll(List.of("enterprise.members.manage", "enterprise.roles.view"))
            && behavior.membershipChangesAllowed(actor.enterpriseId())
            && behavior.operationAllowed(actor.userId(), AccountOperation.CREATE_INVITATION)) {
            result.add("enterprise.invite");
        }
        providers.forEach(provider -> result.addAll(provider.enterprise(actor)));
        return result.stream().distinct().sorted().toList();
    }
}
