import {
  isAdditionalPayloadField,
  isPayloadRecord,
  parseToolPayload,
  payloadEntries,
  payloadFieldLabel,
  type PayloadRecord,
  payloadTextPart
} from "./tool-payload-format";

export type ToolDetailPart = "input" | "result";
export type ToolDetailField = {
  key: string;
  part: ToolDetailPart;
  label: string;
  value: unknown;
  code: boolean;
  error: boolean;
  columns?: unknown;
  notice?: string;
};
export type ToolCallDetails = {
  fields: ToolDetailField[];
  additional: { input?: PayloadRecord; result?: PayloadRecord };
};

const inputFields = [
  "path", "fileName", "filename", "command", "old_text", "new_text", "content", "text", "patch", "diff",
  "url", "query", "pattern", "question", "prompt", "task", "inputText", "input", "target", "destination",
  "recipient", "recipients", "to", "title", "name", "description", "action", "enabled", "status",
  "collection", "collectionName", "fields", "filters", "sort", "scope", "frequency", "localDate", "localTime", "weekdays",
  "monthDay", "timezone", "dueDate", "priority", "ownerName", "teamName", "reason",
];
const resultFields = [
  "path", "fileName", "filename", "name", "title", "stdout", "stderr", "error", "errors", "reason",
  "message", "summary", "content", "text", "result", "structuredContent", "results", "matches", "rows",
  "items", "citations", "data", "description", "status", "times", "date", "time", "localDate", "localTime",
  "timezone", "nextRunAt", "scheduledAt", "dueDate", "enabled", "frequency", "weekdays", "monthDay",
  "enterprise", "user", "owner", "ownerName", "teamName", "hireName", "agentName",
];
const secondaryFields = new Set([
  "limit", "offset", "page", "pageSize", "count", "total", "totalLines", "start_line", "end_line", "startLine", "endLine",
  "revision", "version", "imageRevision", "fileId", "scheduleId", "todoId", "collectionId", "generation",
  "stdoutPath", "stderrPath", "stdoutBytes", "stderrBytes", "sizeBytes", "mediaType", "mimeType",
  "working_directory", "timeout_seconds", "result_paths", "replace_all", "replacements", "maxRetries", "retryCount",
  "createdAt", "updatedAt", "startedAt", "completedAt", "finishedAt", "duration", "durationMs",
  "exitCode", "isError", "timedOut", "capacityExceeded", "truncated", "eof", "rangeComplete", "hasMore",
]);
const repeatedKeys = new Set(["path", "fileName", "filename", "url", "query", "command", "name", "title"]);
const errorKeys = new Set(["error", "errors", "reason"]);
const codeKeys = new Set(["command", "old_text", "new_text", "patch", "diff", "stdout", "stderr"]);

function hasValue(value: unknown) {
  return value !== undefined && value !== null && value !== "" && (!Array.isArray(value) || value.length > 0)
    && (!isPayloadRecord(value) || Object.keys(value).length > 0);
}

/** 只解开标准工具文本包装；包装中的附加字段仍保留在更多内容中。 */
function unpack(source: unknown) {
  let value = source;
  const additional: PayloadRecord = {};
  for (let depth = 0; depth < 4 && isPayloadRecord(value); depth++) {
    const keys = Object.keys(value);
    if (!keys.every((key) => ["content", "structuredContent", "isError", "metadata", "annotations"].includes(key))) {
      break;
    }
    const text = Array.isArray(value.content) && value.content.length === 1 ? payloadTextPart(value.content[0]) : null;
    const parsed = text === null ? undefined : parseToolPayload(text);
    const structured = value.structuredContent;
    if (parsed !== undefined && structured !== undefined && JSON.stringify(parsed) !== JSON.stringify(structured)
      && !(isPayloadRecord(structured) && Object.keys(structured).length === 1 && structured.result === text)) {
      break;
    }
    const next = parsed !== undefined ? parsed : structured;
    if (next === undefined) {
      break;
    }
    const extras = Object.fromEntries(keys.filter((key) => key !== "content" && key !== "structuredContent").map((key) => [key, (value as PayloadRecord)[key]]));
    Object.assign(additional, extras);
    value = next;
  }
  return {value, additional};
}

function isFailure(key: string, value: unknown) {
  if (["isError", "timedOut", "capacityExceeded"].includes(key)) {
    return value === true;
  }
  if (key === "exitCode") {
    return typeof value === "number" && value !== 0;
  }
  return errorKeys.has(key) && value !== false && hasValue(value);
}

function fieldLabel(key: string, part: ToolDetailPart, input: unknown) {
  if (key === "fields" && part === "input") {
    return "查询字段";
  }
  if (key === "path" && isPayloadRecord(input) && ("content" in input || "old_text" in input || "new_text" in input)) {
    return "文件";
  }
  if (key === "content" && isPayloadRecord(input) && "path" in input) {
    return part === "input" ? "文件内容" : "content" in input || "old_text" in input || "new_text" in input ? "返回内容" : "读取内容";
  }
  const labels: Record<string, string> = {
    old_text: "修改前", new_text: "修改后", command: "执行命令", stdout: "执行结果", stderr: "错误输出",
    patch: "修改内容", diff: "修改内容", matches: "搜索结果", filters: "筛选条件", sort: "排序方式",
  };
  return Object.hasOwn(labels, key) ? labels[key] : payloadFieldLabel(key);
}

/** 输入和结果一起选择：保留正文原值，不把主要内容缩短成摘要，也不推断执行成功。 */
export function selectToolCallDetails(inputSource: string, resultSource: string): ToolCallDetails {
  const input = unpack(inputSource ? parseToolPayload(inputSource) : undefined);
  const result = unpack(resultSource ? parseToolPayload(resultSource) : undefined);
  const fields: ToolDetailField[] = [];
  const additional: ToolCallDetails["additional"] = {};
  for (const [part, document] of [["input", input], ["result", result]] as const) {
    const {value} = document;
    const extras: PayloadRecord = Object.assign(Object.create(null), document.additional);
    const addField = (key: string, item: unknown) => {
      const errorContent = part === "result" && ["content", "message", "text", "result"].includes(key)
        && (document.additional.isError === true || isPayloadRecord(value) && value.isError === true);
      const notices: Record<string, string> = {
        isError: "工具返回错误。", timedOut: "命令执行超时。", capacityExceeded: "可用容量不足。",
        truncated: "这里只包含部分内容。", hasMore: "还有未返回的结果。",
      };
      fields.push({
        key,
        part,
        value: item,
        label: errorContent ? "错误信息" : key ? fieldLabel(key, part, input.value) : part === "input" ? "调用内容" : "执行结果",
        code: !errorContent && (codeKeys.has(key) || key === "content" && isPayloadRecord(input.value) && "path" in input.value),
        error: errorContent || part === "result" && isFailure(key, item),
        ...(item === true && Object.hasOwn(notices, key) ? {notice: notices[key]} : {}),
        ...(key === "rows" && isPayloadRecord(value) ? {columns: value.fields} : {})
      });
    };
    if (isPayloadRecord(value)) {
      const entries = payloadEntries(value);
      const preferred = part === "input" ? inputFields : resultFields;
      const known = entries.filter(([key, item]) => preferred.includes(key) && (key !== "stderr" || hasValue(item))
        && !(part === "result" && key === "stdout" && item === "" && hasValue(value.stderr)));
      const unknown = entries.filter(([key]) => !isAdditionalPayloadField(key) && !secondaryFields.has(key));
      const selected = new Set((known.length ? known : unknown).map(([key]) => key));
      for (const [key, item] of entries) {
        const failure = part === "result" && isFailure(key, item);
        const incomplete = ["truncated", "hasMore"].includes(key) && item === true;
        const duplicate = part === "result" && repeatedKeys.has(key) && isPayloadRecord(input.value)
          && Object.hasOwn(input.value, key) && JSON.stringify(input.value[key]) === JSON.stringify(item);
        if (duplicate) {
          continue;
        }
        if (selected.has(key) || failure || incomplete) {
          addField(key, item);
        } else {
          extras[key] = item;
        }
      }
    } else if (value !== undefined) {
      addField("", value);
    }
    for (const [key, item] of Object.entries(document.additional)) {
      if (part === "result" && isFailure(key, item)) {
        addField(key, item);
        delete extras[key];
      }
    }
    if (Object.keys(extras).length) {
      additional[part] = Object.fromEntries(Object.entries(extras));
    }
  }
  const order = (field: ToolDetailField) => {
    const keys = field.part === "input" ? inputFields : resultFields;
    const index = keys.indexOf(field.key);
    return (field.part === "input" ? 0 : 100) + (index < 0 ? keys.length : index);
  };
  fields.sort((left, right) => order(left) - order(right));
  return {fields, additional};
}
