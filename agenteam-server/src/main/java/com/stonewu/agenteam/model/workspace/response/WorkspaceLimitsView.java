package com.stonewu.agenteam.model.workspace.response;

/** 返回当前管理员设置，未保存时沿用部署配置。 */
public record WorkspaceLimitsView(String revision, int workspaceMb, int fileMb, int maximumFiles,
                                  int commandResultsMb) {
}
