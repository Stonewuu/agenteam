package com.stonewu.agenteam.service.permission;

/** 扩展通过独立资源为既有内置角色补充权限，不替换公共模板。 */
public interface BuiltinRoleExtension {
    String resourcePath();
}
