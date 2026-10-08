package com.stonewu.agenteam.service.edition;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.user.entity.UserEntity;

import java.util.List;

/** 已安装组件提供当前可用操作；返回结果不代替业务入口的权限和许可检查。 */
public interface ClientCapabilityProvider {
    default List<String> global(UserEntity user) {
        return List.of();
    }

    default List<String> enterprise(AuthContext actor) {
        return List.of();
    }
}
