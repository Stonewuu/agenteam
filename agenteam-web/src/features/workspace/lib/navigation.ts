import {
  IconBell,
  IconBook2,
  IconBuilding,
  IconCalendarClock,
  IconChartBar,
  IconCheckbox,
  IconCollaboration,
  IconDatabase,
  IconDeviceDesktop,
  IconGitBranch,
  IconKey,
  IconLayoutDashboard,
  IconListDetails,
  IconMail,
  IconMessages,
  IconPlug,
  IconRobot,
  IconSchema,
  IconSettings,
  IconShield,
  IconSparkles,
  IconUsers
} from "@/components/ui/icons";

import {editionNavigationExtension} from "@/features/edition/navigation-extension";
import type {ManagementNavigationItem, PlatformNavigationItem} from "@/features/edition/types/navigation-extension";

export const managementNavigation: readonly ManagementNavigationItem[] = [
  {id: "overview", label: "企业概览", permission: "enterprise.view", icon: IconBuilding},
  {id: "members", label: "成员与邀请", searchTerms: "members invitations invites", permission: "enterprise.members.view", icon: IconUsers},
  {id: "invitations", label: "成员邀请", permission: "enterprise.members.view", icon: IconMail, hidden: true},
  {id: "teams", label: "团队", permission: "enterprise.teams.view", icon: IconGitBranch},
  {id: "roles", label: "角色与权限", searchTerms: "roles permissions", permission: "enterprise.roles.view", icon: IconShield},
  {
    id: "credentials",
    label: "连接凭据",
    permission: "credential.view",
    managePermission: "credential.manage",
    icon: IconKey
  },
  {id: "models", label: "模型配置", searchTerms: "model settings", permission: "model.view", managePermission: "model.manage", icon: IconSettings},
  {id: "integrations", label: "企业协作平台", searchTerms: "messaging sign-in integrations 飞书 企微 机器人 登录 通知", permission: "integration.view", managePermission: "integration.manage", icon: IconPlug},
  {id: "announcements", label: "企业公告", permission: "announcement.view", icon: IconBell},
  {id: "usage", label: "用量统计", searchTerms: "执行用量 模型用量 tokens pricing usage", permission: "usage.view", icon: IconChartBar},
  {id: "tool-logs", label: "调用日志", permission: "tool_log.view", icon: IconListDetails},
  ...editionNavigationExtension.management,
];

export function canViewManagementSection(item: typeof managementNavigation[number], permissions: readonly string[], capabilities: readonly string[] = []): boolean {
  return (!item.capability || capabilities.includes(item.capability))
    && (permissions.includes(item.permission) || (typeof item.managePermission === "string" && permissions.includes(item.managePermission)));
}

export const platformManagementNavigation: readonly PlatformNavigationItem[] = [
  {path: "/management/integrations", label: "企业协作平台", capability: "enterprise.switch", icon: IconPlug},
  {path: "/management/announcements", label: "公告管理", icon: IconBell},
  ...editionNavigationExtension.platform,
];

export const capabilityNavigation = [
  {path: "agents", label: "智能体", permission: "agent", icon: IconRobot},
  {path: "skills", label: "技能", permission: "skill", icon: IconSparkles},
  {path: "plugins", label: "插件", permission: "plugin", icon: IconPlug},
  {path: "workflows", label: "工作流", permission: "workflow", icon: IconSchema},
  {path: "knowledge", label: "知识库", searchTerms: "knowledge bases", permission: "knowledge", icon: IconBook2},
  {path: "data", label: "数据源", permission: "data", icon: IconDatabase},
];

export const workspaceNavigation = [
  {path: "workspace", label: "工作台", permissions: ["workspace.view"], icon: IconLayoutDashboard},
  {path: "conversations", label: "对话任务", permissions: ["conversation.view"], icon: IconMessages},
  {path: "collaboration/tasks", label: "协作任务", permissions: ["agent.run"], icon: IconCollaboration},
  {
    path: "employees",
    label: "数字员工",
    permissions: ["agent.market_view", "agent.run", "agent.hire", "agent.hire_approve"],
    icon: IconUsers
  },
  {path: "schedules", label: "定时任务", permissions: ["schedule.view"], icon: IconCalendarClock},
  {path: "todos", label: "我的待办", permissions: ["todo.view"], icon: IconCheckbox},
  {path: "environment", label: "我的环境", searchTerms: "environment sandbox terminal files 沙盒 命令 文件", permissions: ["agent.run"], icon: IconDeviceDesktop},
];

/** 搜索接口返回业务入口标识，页面路径由正式前端统一定义。 */
export function menuPath(target: string): string | null {
  if (target === "home") {
    return "workspace";
  }
  if (target === "new") {
    return "new-task";
  }
  if (workspaceNavigation.some((item) => item.path === target)) {
    return target;
  }
  if (capabilityNavigation.some((item) => `capabilities/${item.path}` === target)) {
    return target;
  }
  const management = managementNavigation.find((item) => `admin/${item.id}` === target);
  return management ? `management/${management.id}` : null;
}
