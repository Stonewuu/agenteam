package com.stonewu.agenteam.model.enterprise.response;

/**
 * 对外只提供用户编号和当前动作所属企业的显示名。
 */
public record ActorView(String id, String displayName) {
}
