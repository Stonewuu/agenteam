import common from "./en/common.json";
import {editionMessages} from "@/features/edition/messages-extension";
import additional from "./en/additional.json";
import server from "./en/server.json";
import builtins from "./en/builtins.json";
import permissions from "./en/permissions.json";
import auth from "./en/auth.json";
import workspace from "./en/workspace.json";
import agent from "./en/agent.json";
import data from "./en/data.json";
import todo from "./en/todo.json";
import schedule from "./en/schedule.json";
import employee from "./en/employee.json";
import enterprise from "./en/enterprise.json";
import skill from "./en/skill.json";
import knowledge from "./en/knowledge.json";
import exports from "./en/export.json";
import user from "./en/user.json";
import plugin from "./en/plugin.json";
import workflow from "./en/workflow.json";
import resource from "./en/resource.json";
import support from "./en/support.json";
import integration from "./en/integration.json";
import notification from "./en/notification.json";
import scheduleActions from "./en/schedule-actions.json";

/** 界面英文文案按功能拆分；中文原文作为稳定的消息键。 */
export const en: Readonly<Record<string, string>> = {
  ...additional,
  ...server,
  ...builtins,
  ...permissions,
  ...common, ...auth, ...workspace, ...agent, ...data, ...todo, ...schedule, ...employee, ...enterprise,
  ...skill, ...knowledge, ...exports, ...user, ...plugin, ...workflow, ...resource, ...support,
  ...integration, ...notification, ...scheduleActions, ...editionMessages,
};
