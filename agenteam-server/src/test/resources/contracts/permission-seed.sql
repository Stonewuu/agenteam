-- 空的验证数据库使用的首版权限目录；企业角色按 permission-templates.json 创建。
-- 本附件是完整目标定义；当前应用使用实际运行目录中的空库初始化脚本。
SET NAMES utf8mb4;

INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('workspace.view', '进入用户工作台', 'user', 1);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('capabilities.view', '进入能力中心', 'capabilities', 2);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('admin.view', '进入管理端', 'admin', 3);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.view', '查看企业资料', 'admin', 4);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.manage', '管理企业资料', 'admin', 5);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.members.view', '查看成员与邀请', 'admin', 6);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.members.manage', '管理成员与邀请', 'admin', 7);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.teams.view', '查看团队', 'admin', 8);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.teams.manage', '管理团队', 'admin', 9);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.roles.view', '查看角色', 'admin', 10);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.roles.manage', '管理角色', 'admin', 11);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('enterprise.permissions.view', '查看权限目录', 'admin', 12);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.preview', '预览智能体', 'capabilities', 13);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.run', '运行数字员工', 'user', 14);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.market_view', '查看员工广场', 'user', 15);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.hire', '管理本人雇佣', 'user', 16);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.hire_approve', '审批雇佣申请', 'admin', 17);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('resource.grants.manage', '管理资源授权', 'admin', 18);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('resource.manage_all', '维护企业全部能力资源', 'admin', 19);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('credential.manage', '管理凭据引用与轮换', 'admin', 20);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('model.manage', '管理模型提供方与模型', 'admin', 21);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('tag.manage', '管理标签', 'capabilities', 22);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('conversation.view', '查看本人对话', 'user', 23);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('conversation.manage', '管理本人对话', 'user', 24);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('conversation.export', '下载本人对话结果', 'user', 25);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('schedule.view', '查看本人定时任务', 'user', 26);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('schedule.manage', '管理本人定时任务', 'user', 27);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('todo.view', '查看本人待办', 'user', 28);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('todo.manage', '管理本人待办', 'user', 29);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('todo.team_view', '查看授权团队待办', 'user', 30);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('todo.team_manage', '维护授权团队待办', 'user', 31);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('usage.view', '查看企业用量', 'admin', 32);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('usage.manage', '调整用量上限', 'admin', 33);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('audit.view', '查看审计记录', 'admin', 34);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('audit.export', '导出审计记录', 'admin', 35);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('tool_log.view', '查看调用记录摘要', 'admin', 36);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('tool_log.details', '查看脱敏调用详情', 'admin', 37);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('tool_log.export', '导出调用记录', 'admin', 38);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.view', '查看智能体', 'capabilities', 39);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.create', '创建智能体', 'capabilities', 40);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.edit', '编辑智能体', 'capabilities', 41);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.publish', '发布智能体', 'capabilities', 42);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('agent.delete', '删除智能体', 'capabilities', 43);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('skill.view', '查看技能', 'capabilities', 44);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('skill.create', '创建技能', 'capabilities', 45);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('skill.edit', '编辑技能', 'capabilities', 46);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('skill.publish', '发布技能', 'capabilities', 47);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('skill.delete', '删除技能', 'capabilities', 48);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('plugin.view', '查看插件', 'capabilities', 49);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('plugin.create', '创建插件', 'capabilities', 50);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('plugin.edit', '编辑插件', 'capabilities', 51);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('plugin.publish', '发布插件', 'capabilities', 52);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('plugin.delete', '删除插件', 'capabilities', 53);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('workflow.view', '查看工作流', 'capabilities', 54);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('workflow.create', '创建工作流', 'capabilities', 55);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('workflow.edit', '编辑工作流', 'capabilities', 56);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('workflow.publish', '发布工作流', 'capabilities', 57);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('workflow.delete', '删除工作流', 'capabilities', 58);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('knowledge.view', '查看知识库', 'capabilities', 59);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('knowledge.create', '创建知识库', 'capabilities', 60);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('knowledge.edit', '编辑知识库', 'capabilities', 61);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('knowledge.publish', '发布知识库', 'capabilities', 62);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('knowledge.delete', '删除知识库', 'capabilities', 63);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('data.view', '查看数据源', 'capabilities', 64);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('data.create', '创建数据源', 'capabilities', 65);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('data.edit', '编辑数据源', 'capabilities', 66);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('data.publish', '发布数据源', 'capabilities', 67);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('data.delete', '删除数据源', 'capabilities', 68);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('skill.import', '导入技能', 'capabilities', 69);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('skill.export', '导出技能', 'capabilities', 70);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('skill.use', '在任务中使用技能', 'capabilities', 71);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('plugin.test', '检查插件连接', 'capabilities', 72);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('plugin.invoke', '在任务中调用授权工具', 'capabilities', 73);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('workflow.preview', '测试工作流', 'capabilities', 74);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('knowledge.search', '检索授权知识', 'capabilities', 75);
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `sort_no`)
VALUES ('data.query', '查询授权数据', 'capabilities', 76);
