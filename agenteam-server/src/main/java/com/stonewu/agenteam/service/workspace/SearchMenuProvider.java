package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.workspace.entity.SearchMenu;

import java.util.List;

/** 已安装扩展提供当前可用的搜索入口，不扩展成员本身的权限。 */
public interface SearchMenuProvider {
    List<SearchMenu> menus(AuthContext actor);
}
