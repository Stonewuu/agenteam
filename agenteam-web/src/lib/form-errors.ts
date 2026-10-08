const fieldLabels: Record<string, string> = {
  sources: "工具来源",
  tools: "工具选择",
  sourceId: "工具来源",
  versionId: "版本",
  toolId: "执行工具",
  subagentVersionIds: "子智能体",
  dynamicSubagentEnabled: "临时助手",
  approvalPolicy: "工具审批策略",
  name: "名称",
  displayName: "显示名",
  description: "简介",
  username: "账号",
  identifier: "账号或邮箱",
  email: "邮箱",
  contactEmail: "联系邮箱",
  password: "密码",
  currentPassword: "当前密码",
  newPassword: "新密码",
  confirmPassword: "确认密码",
  timezone: "时区",
  enabled: "启用状态",
  title: "标题",
  body: "内容",
  reason: "原因",
  dueDate: "截止日期",
  priority: "优先级",
  monthlyLimit: "每月上限",
  businessRole: "业务职责",
  modelProfileId: "模型",
  modelSelection: "模型选择",
  reasoningEffort: "思考强度",
  reasoningEfforts: "支持的思考强度",
  instructions: "执行指令",
  inputDescription: "输入说明",
  outputDescription: "输出说明",
  welcomeMessage: "欢迎语",
  suggestedQuestions: "建议问题",
  publicExamples: "典型工作",
  maxSteps: "最多执行步骤",
  timeoutSeconds: "等待时间上限",
  endpoint: "服务地址",
  credentialId: "连接凭据",
  transport: "连接方式",
  tagIds: "标签",
  roleIds: "角色",
  teamIds: "团队",
  ownerUserId: "负责人",
  memberIds: "团队成员",
  dataScope: "数据范围",
  permissions: "权限",
  releaseNote: "发布说明",
  "capabilities.maxOutputTokens": "输出长度上限",
};

export function validationMessage(message: string): string {
  const text = message.trim().replace(/^字段\s*[「“"'].*?[」”"']\s*[：:]\s*/, "");
  if (text.includes("布尔值")) {
    return "请重新选择此选项。";
  }
  const length = text.match(/^字符数需要在\s*(\d+)\s*[～~至-]\s*(\d+)\s*之间[。.]?$/);
  if (length) {
    return length[1] === "0" ? `最多填写 ${length[2]} 个字符。` : `请输入 ${length[1]}～${length[2]} 个字符。`;
  }
  return text;
}

/** 原始字段保留在接口异常中，页面只使用用户能理解的字段名称。 */
export function fieldErrorMessages(fields: Record<string, string[]>, text: (message: string) => string = (message) => message): string[] {
  return [...new Set(Object.entries(fields).flatMap(([field, messages]) => {
    const normalized = field.replace(/\.\d+(?=\.|$)/g, "");
    const label = fieldLabels[normalized] ?? fieldLabels[normalized.split(".").at(-1) ?? ""];
    return messages.map(validationMessage).filter(Boolean).map((message) => label && !message.startsWith(label) ? `${text(label)}${text("：")}${text(message)}` : text(message));
  }))].slice(0, 20);
}
