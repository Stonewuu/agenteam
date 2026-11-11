package com.stonewu.agenteam.model.workspace.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/** 平台保存的工作文件与命令输出容量，单位为 MB。 */
public record WorkspaceLimitValues(int workspaceMb, int fileMb, int maximumFiles, int commandResultsMb) {
    public WorkspaceLimitValues {
        if (workspaceMb < 16 || fileMb < 1 || fileMb > workspaceMb || maximumFiles < 10 || commandResultsMb < 1) {
            throw new IllegalArgumentException(Messages.text("error.workspace.workspaceLimitValues.init.1"));
        }
    }
}
