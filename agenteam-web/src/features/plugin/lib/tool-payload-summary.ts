import {
  formatPayloadScalar,
  isAdditionalPayloadField,
  isPayloadRecord,
  parseToolPayload,
  payloadEntries,
  payloadFieldLabel,
  payloadTextPart
} from "./tool-payload-format";

export type ToolPayloadPart = "input" | "result";
export type ToolPayloadSummary = {
  fields: { key: string; text: string; error: boolean }[];
  text?: string;
  more: boolean;
  details?: unknown;
};

/** 只缩短展示文本，完整值保留给“更多内容”和复制。 */
export function abbreviatePayloadText(value: string, maximum = 180, lines = 2) {
  const head = value.slice(0, maximum + 1).split("\n", lines + 1);
  let text = head.slice(0, lines).join("\n").slice(0, maximum);
  if (/[\uD800-\uDBFF]$/.test(text)) {
    text = text.slice(0, -1);
  }
  const shortened = text.length < value.length;
  return {text: shortened ? text.trimEnd() + "…" : text, shortened};
}

function compactValue(value: unknown, field: string, timezone?: string | null, depth = 0, t: (message: string) => string = (message) => message, locale = "zh-CN"): {
  text: string;
  shortened: boolean
} {
  if (depth >= 2 && value !== null && typeof value === "object") {
    return {text: "…", shortened: true};
  }
  if (Array.isArray(value)) {
    if (!value.length) {
      return {text: t("暂无条目"), shortened: false};
    }
    const items = value.slice(0, 2).map((item) => compactValue(item, field, timezone, depth + 1, t, locale));
    const summary = abbreviatePayloadText(items.map((item) => item.text).join("；"));
    return {
      text: summary.text,
      shortened: summary.shortened || value.length > 2 || items.some((item) => item.shortened)
    };
  }
  if (isPayloadRecord(value)) {
    const entries = payloadEntries(value);
    if (!entries.length) {
      return {text: t("无"), shortened: false};
    }
    const named = entries.filter(([key]) => ["title", "name", "path", "text", "message", "status"].includes(key));
    const chosen = (named.length ? named : entries).slice(0, 2);
    const text = chosen.map(([key, item]) => `${payloadFieldLabel(key, t)}：${compactValue(item, key, timezone, depth + 1, t, locale).text}`).join("；");
    return {text: abbreviatePayloadText(text).text, shortened: true};
  }
  return abbreviatePayloadText(formatPayloadScalar(value, field, timezone, t, locale));
}

function meaningful(value: unknown) {
  return value !== null && value !== undefined && value !== "" && (!Array.isArray(value) || value.length > 0);
}

function errorField(key: string, value: unknown) {
  if (["isError", "timedOut", "capacityExceeded"].includes(key)) {
    return value === true;
  }
  if (key === "exitCode") {
    return typeof value === "number" && value !== 0;
  }
  return ["error", "errors", "stderr"].includes(key) && meaningful(value);
}

function primaryKeys(value: Record<string, unknown>, part: ToolPayloadPart) {
  if (part === "input") {
    if ("command" in value) {
      return ["command", "working_directory"];
    }
    if ("frequency" in value) {
      return ["frequency", "localDate", "localTime", "weekdays", "monthDay"];
    }
    if ("path" in value || "pattern" in value) {
      return ["path", "pattern", "start_line", "end_line", "startLine", "endLine"];
    }
    return ["url", "query", "target", "to", "recipient", "recipients", "destination", "title", "name", "question", "prompt", "task", "inputText", "action", "enabled", "status"];
  }
  if ("exitCode" in value) {
    return ["exitCode", "stderr", "stdout"];
  }
  if ("path" in value) {
    return ["path", "message", "content", "replacements"];
  }
  return ["error", "errors", "reason", "message", "summary", "result", "text", "content", "results", "rows", "items", "matches", "citations", "status", "count", "total", "title", "name"];
}

/** 仅解开标准文本包装，不通过工具名称推测业务结果。 */
function unwrap(value: unknown): unknown {
  let current = value;
  for (let depth = 0; depth < 4 && isPayloadRecord(current); depth++) {
    const keys = Object.keys(current);
    if (!keys.every((key) => ["content", "structuredContent", "isError", "metadata", "annotations"].includes(key))) {
      break;
    }
    const text = Array.isArray(current.content) && current.content.length === 1 ? payloadTextPart(current.content[0]) : null;
    const next = current.structuredContent ?? (text === null ? undefined : parseToolPayload(text));
    if (next === undefined) {
      break;
    }
    if (current.isError === true) {
      return isPayloadRecord(next) ? {...next, isError: true} : {error: next};
    }
    current = next;
  }
  return current;
}

/** 首段不是完整的结构化数据时，只采用已经读完的顶层字段，不补写缺失值。 */
function prefixFields(source: string): Record<string, unknown> | null {
  let cursor = source.search(/\S/);
  if (source[cursor] !== "{") {
    return null;
  }
  cursor++;
  const fields: Record<string, unknown> = Object.create(null);
  while (cursor < source.length) {
    while (/\s/.test(source[cursor] ?? "")) {
      cursor++;
    }
    if (source[cursor] !== '"') {
      break;
    }
    const keyEnd = jsonValueEnd(source, cursor);
    if (keyEnd === null) {
      break;
    }
    let key: string;
    try {
      key = JSON.parse(source.slice(cursor, keyEnd));
    } catch {
      break;
    }
    cursor = keyEnd;
    while (/\s/.test(source[cursor] ?? "")) {
      cursor++;
    }
    if (source[cursor++] !== ":") {
      break;
    }
    while (/\s/.test(source[cursor] ?? "")) {
      cursor++;
    }
    const valueEnd = jsonValueEnd(source, cursor);
    if (valueEnd === null) {
      break;
    }
    try {
      const text = source.slice(cursor, valueEnd);
      JSON.parse(text);
      fields[key] = parseToolPayload(text);
    } catch {
      break;
    }
    cursor = valueEnd;
    while (/\s/.test(source[cursor] ?? "")) {
      cursor++;
    }
    if (source[cursor++] !== ",") {
      break;
    }
  }
  return Object.keys(fields).length ? fields : null;
}

function jsonValueEnd(source: string, start: number): number | null {
  let depth = 0;
  let quoted = false;
  let escaped = false;
  for (let index = start; index < source.length; index++) {
    const character = source[index];
    if (quoted) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        quoted = false;
        if (depth === 0) {
          return index + 1;
        }
      }
    } else if (character === '"') {
      quoted = true;
    } else if (character === "{" || character === "[") {
      depth++;
    } else if (character === "}" || character === "]") {
      if (depth === 0) {
        return index;
      }
      depth--;
      if (depth === 0) {
        return index + 1;
      }
    } else if (character === "," && depth === 0) {
      return index;
    }
  }
  return null;
}

export function summarizeToolPayload(source: string, part: ToolPayloadPart, complete: boolean, timezone?: string | null, t: (message: string) => string = (message) => message, locale = "zh-CN"): ToolPayloadSummary {
  let original = parseToolPayload(source);
  if (!complete && typeof original === "string") {
    original = prefixFields(source) ?? original;
  }
  const value = unwrap(original);
  if (!isPayloadRecord(value)) {
    const summary = compactValue(value, "", timezone, 0, t, locale);
    return {fields: [], text: summary.text, more: !complete || summary.shortened || value !== original};
  }
  const entries = payloadEntries(value);
  if (!entries.length) {
    return {
      fields: [],
      text: complete ? t(part === "input" ? "无需参数" : "没有返回内容") : undefined,
      more: !complete || value !== original
    };
  }
  const byKey = new Map(entries);
  const errors = part === "result" ? entries.filter(([key, item]) => errorField(key, item)).map(([key]) => key) : [];
  const preferred = [...new Set([...errors, ...primaryKeys(value, part)])].filter((key) => byKey.has(key) && meaningful(byKey.get(key)));
  const fallback = entries.filter(([key, item]) => !isAdditionalPayloadField(key) && meaningful(item)).map(([key]) => key);
  const selected = (preferred.length ? preferred : fallback.length ? fallback : entries.map(([key]) => key)).slice(0, 3);
  const zone = typeof value.timezone === "string" ? value.timezone : timezone;
  const brief = selected.map((key) => ({
    key, ...compactValue(byKey.get(key), key, zone, 0, t, locale),
    error: part === "result" && errorField(key, byKey.get(key))
  }));
  const fullyShown = new Set(brief.filter((item) => !item.shortened).map((item) => item.key));
  const remaining = entries.filter(([key]) => !fullyShown.has(key));
  const details = complete && value === original ? Object.fromEntries(remaining) : undefined;
  return {fields: brief, more: !complete || remaining.length > 0 || value !== original, details};
}
