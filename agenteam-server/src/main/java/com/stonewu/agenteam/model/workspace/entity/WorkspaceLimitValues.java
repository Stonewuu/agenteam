package com.stonewu.agenteam.model.workspace.entity;

import com.stonewu.agenteam.model.system.entity.LimitValue;

import com.stonewu.agenteam.service.i18n.Messages;

/** 平台保存的工作文件与命令输出容量，单位为 MB。 */
public record WorkspaceLimitValues(int workspaceMb, int fileMb, int maximumFiles, int commandResultsMb) {
    public WorkspaceLimitValues {
        if (!LimitValue.validInteger(workspaceMb, 16) || !LimitValue.validInteger(fileMb, 1)
            || !LimitValue.ordered(fileMb, workspaceMb) || !LimitValue.validInteger(maximumFiles, 10)
            || !LimitValue.validInteger(commandResultsMb, 1)) {
            throw new IllegalArgumentException(Messages.text("error.workspace.workspaceLimitValues.init.1"));
        }
    }
}
