/** 只格式化接口已经公开的内容，不查询额外数据，也不推断工具执行结果。 */
export type PayloadRecord = Record<string, unknown>;
export type PayloadColumn = { key: string; label: string };

export function isPayloadRecord(value: unknown): value is PayloadRecord {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function parseToolPayload(source: string): unknown {
  try {
    return JSON.parse(source, (_key, value, context?: { source: string }) => {
      // 大整数编号不能在展示时被四舍五入；旧浏览器没有原文时保留整个文本。
      if (typeof value === "number" && (!Number.isFinite(value) || Number.isInteger(value) && !Number.isSafeInteger(value))) {
        if (context?.source) {
          return context.source;
        }
        throw new RangeError("数值需要保留原文");
      }
      return value;
    });
  } catch {
    return source;
  }
}

export type ToolPayloadFile = {
  path: string | null;
  fileId: string | null;
  name: string | null;
  width?: number;
  height?: number;
  revision: string | null
};

/** 只使用完整公开字段，截短预览中的路径不能作为文件地址。 */
export function toolPayloadFile(source: string): ToolPayloadFile | null {
  const value = parseToolPayload(source);
  if (!isPayloadRecord(value) || value.truncated === true) {
    return null;
  }
  const text = (key: string) => {
    const field = value[key];
    return typeof field === "string" && field.trim() ? field : null;
  };
  const dimension = (key: string) => {
    const field = value[key];
    return typeof field === "number" && Number.isSafeInteger(field) && field > 0 ? field : undefined;
  };
  return {
    path: text("path"),
    fileId: text("fileId"),
    name: text("name"),
    width: dimension("width"),
    height: dimension("height"),
    revision: text("imageRevision") ?? text("revision")
  };
}

/** 易读详情省略已经展示的相同字段，原始内容和复制内容不经过此处理。 */
export function omitRepeatedPayloadFields(source: string, repeatedFields?: PayloadRecord): string {
  if (!repeatedFields) {
    return source;
  }
  const value = parseToolPayload(source);
  if (!isPayloadRecord(value) || value.truncated === true) {
    return source;
  }
  const original = Object.entries(value);
  const entries = original.filter(([key, item]) => !Object.hasOwn(repeatedFields, key) || item !== repeatedFields[key]);
  if (entries.length === original.length) {
    return source;
  }
  const remaining = Object.fromEntries(entries);
  return payloadEntries(remaining).length ? JSON.stringify(remaining) : "";
}

/** 失败摘要只采用已公开的错误说明，完整内容继续保留在调用详情中。 */
export function toolPayloadFailure(source: string): string | null {
  const value = parseToolPayload(source);
  let message: string | null = null;
  if (typeof value === "string" && !/^[\s]*[\[{]/.test(value)) {
    message = value;
  } else if (isPayloadRecord(value)) {
    const error = isPayloadRecord(value.error) ? value.error.message : value.error;
    const content = Array.isArray(value.content) ? value.content.map(payloadTextPart).filter(Boolean).join("\n") : value.content;
    message = [error, value.message, content, value.stderr].find((item): item is string => typeof item === "string" && Boolean(item.trim()) && !/^[\s]*[\[{]/.test(item)) ?? null;
  }
  if (!message?.trim()) {
    return null;
  }
  const characters = Array.from(message.trim());
  return characters.length > 240 ? characters.slice(0, 240).join("") + "…" : message.trim();
}

/** 只调整合法 JSON 的空白，保留数值、转义字符和重复字段的原文。 */
export function formatRawToolPayload(source: string): string {
  try {
    JSON.parse(source);
  } catch {
    return source;
  }
  const tokens = source.match(/"(?:\\[\s\S]|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null|[{}\[\],:]/g) ?? [];
  let indent = 0;
  let output = "";
  const newline = () => `\n${"  ".repeat(indent)}`;
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    if (token === "{" || token === "[") {
      output += token;
      if (tokens[index + 1] === (token === "{" ? "}" : "]")) {
        output += tokens[++index];
      } else {
        indent++;
        if (indent > 50) {
          return source;
        }
        output += newline();
      }
    } else if (token === "}" || token === "]") {
      indent--;
      output += newline() + token;
    } else if (token === ",") {
      output += token + newline();
    } else if (token === ":") {
      output += ": ";
    } else {
      output += token;
    }
  }
  return output;
}

const fieldLabels: Record<string, string> = {
  name: "名称",
  title: "标题",
  description: "说明",
  content: "内容",
  text: "文本",
  message: "消息",
  summary: "摘要",
  query: "搜索内容",
  question: "问题",
  prompt: "任务要求",
  task: "任务",
  inputText: "任务内容",
  input: "输入",
  result: "结果",
  command: "命令",
  pattern: "搜索内容",
  start_line: "起始行",
  end_line: "结束行",
  glob: "文件范围",
  matches: "搜索结果",
  filters: "筛选条件",
  sort: "排序方式",
  field: "字段",
  operator: "匹配条件",
  direction: "排序方向",
  ignore_case: "忽略大小写",
  mode: "匹配方式",
  before: "前文行数",
  after: "后文行数",
  max_matches: "最多匹配数",
  target: "目标",
  destination: "目标",
  recipient: "收件人",
  recipients: "收件人",
  to: "收件人",
  action: "操作",
  url: "网址",
  uri: "资源地址",
  path: "路径",
  fileName: "文件名",
  filename: "文件名",
  mimeType: "文件类型",
  mediaType: "内容类型",
  sizeBytes: "文件大小",
  count: "数量",
  total: "总数",
  limit: "最多返回",
  offset: "起始位置",
  page: "页码",
  pageSize: "每页数量",
  stdout: "输出内容",
  stderr: "错误输出",
  stdoutPath: "输出文件",
  stderrPath: "错误输出文件",
  stdoutBytes: "输出大小",
  stderrBytes: "错误输出大小",
  exitCode: "退出码",
  timedOut: "已超时",
  capacityExceeded: "容量不足",
  working_directory: "工作目录",
  timeout_seconds: "时间上限（秒）",
  result_paths: "使用的工具结果",
  old_text: "替换原文",
  new_text: "新内容",
  replace_all: "替换全部",
  replacements: "替换次数",
  startLine: "起始行",
  endLine: "结束行",
  totalLines: "总行数",
  rangeComplete: "请求范围已读完",
  eof: "已读到文件末尾",
  isDirectory: "目录",
  items: "条目",
  rows: "查询结果",
  fields: "字段说明",
  dataFields: "字段说明",
  results: "结果列表",
  data: "数据",
  structuredContent: "返回数据",
  annotations: "补充信息",
  metadata: "补充信息",
  type: "类型",
  label: "显示名称",
  value: "值",
  enabled: "是否启用",
  status: "状态",
  isError: "是否出错",
  error: "错误",
  errors: "错误信息",
  reason: "原因",
  hasMore: "还有更多结果",
  truncated: "内容已截取",
  sensitive: "敏感字段",
  frequency: "执行频率",
  localDate: "执行日期",
  localTime: "执行时间",
  weekdays: "每周执行日",
  monthDay: "每月执行日",
  timezone: "时区",
  timeZone: "时区",
  date: "日期",
  time: "时间",
  times: "执行时间",
  nextRunAt: "下次执行",
  createdAt: "创建时间",
  updatedAt: "更新时间",
  startedAt: "开始时间",
  completedAt: "完成时间",
  finishedAt: "结束时间",
  expiresAt: "有效期至",
  scheduledAt: "计划时间",
  dueDate: "截止日期",
  maxRetries: "最多重试次数",
  retryCount: "重试次数",
  owner: "负责人",
  ownerName: "负责人",
  displayName: "显示名称",
  teamName: "团队",
  priority: "优先级",
  scope: "范围",
  enterprise: "企业",
  user: "成员",
  employee: "数字员工",
  agentName: "数字员工",
  hireName: "执行员工",
  id: "记录编号",
  hireId: "员工编号",
  agentId: "智能体编号",
  agentVersionId: "员工版本编号",
  scheduleId: "计划编号",
  todoId: "待办编号",
  ownerUserId: "负责人编号",
  userId: "成员编号",
  teamId: "团队编号",
  fileId: "附件编号",
  revision: "修改版本",
  version: "版本",
  cursor: "分页位置",
  nextCursor: "下一页位置",
  citations: "引用资料",
  collection: "数据集合",
  collectionName: "数据集合",
  documentName: "文档名称",
  snippet: "摘录",
  score: "分数",
};

const fieldValues: Record<string, Record<string, string>> = {
  frequency: {once: "仅一次", daily: "每天", weekly: "每周", monthly: "每月"},
  priority: {normal: "普通", high: "高"},
  operator: {
    eq: "等于",
    ne: "不等于",
    gt: "大于",
    gte: "大于或等于",
    lt: "小于",
    lte: "小于或等于",
    contains: "包含",
    in: "属于以下值",
    is_null: "为空"
  },
  direction: {asc: "升序", desc: "降序"},
  mode: {literal: "按原文匹配", regex: "使用正则表达式"},
  status: {
    pending: "待处理",
    in_progress: "进行中",
    running: "执行中",
    completed: "已完成",
    succeeded: "已完成",
    failed: "失败",
    cancelled: "已取消",
    queued: "等待执行",
    waiting_approval: "等待确认",
    skipped: "已跳过"
  },
};

const additionalFields = new Set(["id", "hireId", "agentId", "ownerUserId", "userId", "teamId", "revision", "cursor", "nextCursor", "agentVersionId", "metadata", "annotations", "fields", "dataFields"]);
const leadingFields = ["name", "title", "content", "message", "summary", "description", "inputText", "query", "question"];
const internalFields = new Set(["workspaceVersion", "workspaceExport", "sourceResultIds", "inputFileIds", "_toolResultFile"]);
export const payloadPageSize = 20;

export function payloadFieldLabel(key: string, t: (message: string) => string = (message) => message): string {
  return Object.hasOwn(fieldLabels, key) ? t(fieldLabels[key]) : key;
}

export function isAdditionalPayloadField(key: string): boolean {
  return additionalFields.has(key);
}

export function payloadEntries(value: PayloadRecord, showAllFields = false): [string, unknown][] {
  const priority = (key: string) => leadingFields.includes(key) ? leadingFields.indexOf(key) : leadingFields.length;
  const structured = value.structuredContent;
  const text = Array.isArray(value.content) && value.content.length === 1 ? payloadTextPart(value.content[0]) : null;
  const duplicate = !showAllFields && text !== null && structured !== undefined && (
    JSON.stringify(parseToolPayload(text)) === JSON.stringify(structured)
    || isPayloadRecord(structured) && Object.keys(structured).length === 1 && structured.result === text
  );
  return Object.entries(value).filter(([key]) => (showAllFields || !internalFields.has(key)) && (!duplicate || key !== "structuredContent")).sort(([left], [right]) => priority(left) - priority(right));
}

export function isPayloadMarkdown(value: string, field: string): boolean {
  return ["", "content", "text", "result", "description", "summary", "message"].includes(field)
    && /(?:^|\n)(?:#{1,6} |[-*+] |\d+\. |```|> )|\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\)/.test(value);
}

export function formatPayloadScalar(value: unknown, key = "", timezone?: string | null, t: (message: string) => string = (message) => message, locale = "zh-CN"): string {
  if (value === null) {
    return t("空值");
  }
  if (value === undefined) {
    return t("未提供");
  }
  if (value === "") {
    return t("空文本");
  }
  if (typeof value === "boolean") {
    return t(value ? "是" : "否");
  }
  if (typeof value === "string") {
    if ((key === "path" || key === "working_directory") && (value === "." || value === "./")) {
      return t("当前目录");
    }
    const values = Object.hasOwn(fieldValues, key) ? fieldValues[key] : undefined;
    if (values && Object.hasOwn(values, value)) {
      return t(values[value]);
    }
    // 仅转换明确携带时区的时间，不把编号、普通文本或本地日期当作时间。
    if (timezone && /^(?:time|times|nextRunAt|createdAt|updatedAt|startedAt|completedAt|finishedAt|expiresAt|scheduledAt)$/.test(key)
      && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:[\d.]+(?:Z|[+-]\d{2}:\d{2})$/.test(value)) {
      const date = new Date(value);
      if (Number.isFinite(date.getTime())) {
        try {
          const formatted = new Intl.DateTimeFormat(locale, {
            timeZone: timezone,
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            hourCycle: "h23"
          }).format(date);
          return locale.startsWith("zh") ? `${formatted}（${timezone}）` : `${formatted} (${timezone})`;
        } catch { /* 无效时区保持原文。 */
        }
      }
    }
  }
  return String(value);
}

/** 仅采用服务端数据字段的真实名称和标签，未列出的返回字段也必须保留。 */
export function payloadColumns(rows: unknown[], definitions?: unknown, t: (message: string) => string = (message) => message): PayloadColumn[] | null {
  if (rows.length === 0 || !rows.every(isPayloadRecord)) {
    return null;
  }
  const keys = [...new Set(rows.flatMap((row) => Object.keys(row)))];
  if (!keys.length || keys.length > 8 || rows.some((row) => Object.values(row).some((value) => value !== null && typeof value === "object"))) {
    return null;
  }
  if (!Array.isArray(definitions) && (keys.some(isAdditionalPayloadField) || rows.some((row) => Object.values(row).some((value) => typeof value === "string" && value.length > 160)))) {
    return null;
  }
  const labels = new Map<string, string>();
  if (Array.isArray(definitions)) {
    for (const field of definitions) {
      if (isPayloadRecord(field) && typeof field.name === "string" && typeof field.label === "string") {
        labels.set(field.name, field.label);
      }
    }
  }
  return keys.map((key) => ({key, label: labels.get(key) ?? payloadFieldLabel(key, t)}));
}

/** 外部工具的标准文本块可直接阅读；带其他信息的块仍显示全部字段。 */
export function payloadTextPart(value: unknown): string | null {
  return isPayloadRecord(value) && value.type === "text" && typeof value.text === "string"
  && Object.keys(value).every((key) => key === "type" || key === "text") ? value.text : null;
}
