package com.stonewu.agenteam.model.skill.response;

import com.stonewu.agenteam.model.agent.response.EmployeeView;

import java.util.List;

/**
 * 只展示至少有一位已雇佣员工确实能够执行的技能。
 */
public record WorkspaceSkillView(InputSkillOptionView skill, List<EmployeeView> employees) {
}
