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
  group_collaboration: {
    collab_list_roles: "查看协作岗位",
    collab_read_context: "查看任务背景",
    collab_read_artifact: "读取协作成果",
    collab_publish_artifact: "保存协作成果",
    collab_assign: "委派岗位工作",
    collab_handoff: "交接负责岗位",
    collab_wait: "等待协作成果",
    collab_ask_user: "请求补充信息",
    collab_submit_result: "提交岗位成果",
    collab_complete_task: "完成协作任务"
  },
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
  "文件": "workspace_files",
  "团队协作": "group_collaboration"
};

const builtinDescriptions: Record<string, string> = {
  platform_context: "查看当前企业、账号和企业时区。",
  platform_time: "查询当前日期、时间和企业时区。",
  platform_search: "搜索你有权访问的工作空间内容。",
  todo_list: "查询个人和团队待办。",
  todo_get: "查看待办的内容、负责人和处理进展。",
  todo_history: "查看待办的修改历史。",
  todo_assignees: "查找可负责此待办的企业成员。",
  todo_teams: "查找可以分配待办的团队。",
  todo_create: "创建待办并设置负责人和截止日期。",
  todo_update: "修改待办内容、负责人或截止日期。",
  todo_set_status: "将待办标为待处理、进行中、已完成或已取消。",
  todo_transfer: "将待办转交给其他负责人。",
  todo_delete: "删除指定待办。",
  schedule_list: "查看自己的定时任务。",
  schedule_get: "查看定时任务的内容和时间安排。",
  schedule_occurrences: "查看定时任务的执行记录和结果。",
  schedule_employees: "查找可执行定时任务的数字员工。",
  schedule_preview_times: "预览接下来五次执行时间。",
  schedule_create: "选择数字员工，设置任务内容和执行时间。",
  schedule_update: "修改定时任务的内容和时间安排。",
  schedule_set_enabled: "启用或暂停定时任务。",
  schedule_upgrade_version: "更新后续定时任务使用的员工版本。",
  schedule_run_once: "立即运行一次已有的定时任务。",
  schedule_delete: "删除已暂停的定时任务，保留执行记录。",
  read_url: "读取网页内容，作为工作参考。",
  collab_list_roles: "查看参与当前任务的岗位和职责。",
  collab_read_context: "查看任务要求、补充信息和工作进展。",
  collab_read_artifact: "读取其他岗位提交的协作成果。",
  collab_publish_artifact: "保存成果，供参与任务的岗位使用。",
  collab_assign: "将一项工作委派给其他岗位。",
  collab_handoff: "将任务的负责工作交接给其他岗位。",
  collab_wait: "等待其他岗位完成所需成果。",
  collab_ask_user: "向任务发起人询问需要补充的信息。",
  collab_submit_result: "提交当前岗位完成的工作成果。",
  collab_complete_task: "汇总成果并完成协作任务。",
  read_file: "读取项目中的文件内容。",
  view_image: "查看项目中的图片。",
  grep_files: "在项目文件中搜索指定内容。",
  list_files: "查看项目目录和文件。",
  write_file: "在项目中创建文件。",
  edit_file: "修改项目中的文件。",
  execute: "运行命令，处理项目中的工作。",
  export_file: "将工作文件保存为可下载的附件。",
  read_agent_spawn_result: "查看子智能体完成的工作结果。",
  read_agent_send_result: "查看子智能体完成的工作结果。",
  read_workflow_result: "查看工作流执行结果。",
};

export function toolDisplayName(tool: {
  name: string;
  displayName?: string
}, builtinCode?: string | null, text: (message: string) => string = (message) => message): string {
  const builtin = builtinCode && builtinNames[builtinResources[builtinCode] ?? builtinCode]?.[tool.name];
  return builtin ? text(builtin) : tool.displayName?.trim() || tool.name;
}

/** 内置工具的页面说明与模型执行指令分开；外部工具和用户说明保持原样。 */
export function toolDisplayDescription(tool: {name: string; description: string}, builtinCode?: string | null,
  text: (message: string) => string = (message) => message): string {
  const known = builtinCode && builtinNames[builtinResources[builtinCode] ?? builtinCode]?.[tool.name];
  return known && builtinDescriptions[tool.name] ? text(builtinDescriptions[tool.name]) : tool.description;
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
