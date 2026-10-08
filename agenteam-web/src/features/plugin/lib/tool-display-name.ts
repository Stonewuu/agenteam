const builtinNames: Record<string, Record<string, string>> = {
  platform_basics: {
    platform_context: "查看工作空间信息",
    platform_time: "查询当前时间",
    platform_search: "搜索工作空间"
  },
  todo_management: {
    todo_list: "查询待办",
    todo_get: "查看待办详情",
    todo_history: "查看待办历史",
    todo_assignees: "查找待办负责人",
    todo_teams: "查询待办团队",
    todo_create: "创建待办",
    todo_update: "修改待办",
    todo_set_status: "更新待办状态",
    todo_transfer: "转交待办",
    todo_delete: "删除待办"
  },
  schedule_management: {
    schedule_list: "查询计划",
    schedule_get: "查看计划详情",
    schedule_occurrences: "查看执行记录",
    schedule_employees: "查询执行员工",
    schedule_preview_times: "预览执行时间",
    schedule_create: "创建计划",
    schedule_update: "修改计划",
    schedule_set_enabled: "启用或暂停计划",
    schedule_upgrade_version: "更新计划员工版本",
    schedule_run_once: "立即执行计划",
    schedule_delete: "删除计划"
  },
  web_read: {read_url: "读取网页"},
  workspace_files: {
    read_file: "读取文件",
    view_image: "查看图片",
    grep_files: "搜索文件内容",
    list_files: "列出文件",
    write_file: "创建文件",
    edit_file: "编辑文件",
    execute: "执行命令",
    export_file: "导出文件",
    read_agent_spawn_result: "子智能体结果",
    read_agent_send_result: "子智能体结果",
    read_workflow_result: "工作流结果"
  },
};
const builtinResources: Record<string, string> = {
  "平台基础": "platform_basics",
  "待办管理": "todo_management",
  "定时任务": "schedule_management",
  "网页读取": "web_read",
  "文件": "workspace_files"
};

export function toolDisplayName(tool: {
  name: string;
  displayName?: string
}, builtinCode?: string | null, text: (message: string) => string = (message) => message): string {
  const builtin = builtinCode && builtinNames[builtinResources[builtinCode] ?? builtinCode]?.[tool.name];
  return builtin ? text(builtin) : tool.displayName?.trim() || tool.name;
}

export function toolOperationLabel(operationClass: string): string {
  return ({
    read: "只读",
    write: "修改",
    destructive: "高敏感",
    unknown: "未分类"
  } as Record<string, string>)[operationClass] ?? "未分类";
}

/** 旧对话没有插件编号，只转换已登记内置插件的完整旧标题，不猜测外部同名方法。 */
export function localizeSavedToolLabel(label: string, text: (message: string) => string = (message) => message): string {
  const match = /^(.+?)\s*[：:/·]\s*(.+)$/.exec(label.trim());
  if (!match) {
    return label;
  }
  const [, resource, name] = match;
  const names = builtinNames[builtinResources[resource.trim()]];
  const title = names?.[name] ?? (names && Object.values(names).includes(name) ? name : undefined);
  return title ? `${text(resource.trim())} · ${text(title)}` : label;
}
