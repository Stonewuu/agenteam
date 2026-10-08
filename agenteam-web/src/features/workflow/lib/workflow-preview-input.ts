import {record} from "./workflow-graph";

/** 仅生成可编辑的示例输入；服务端仍按完整字段约束验证。 */
export function previewExample(schema: Record<string, unknown>, depth = 0): unknown {
  if (depth > 10) {
    return null;
  }
  if (Object.hasOwn(schema, "default")) {
    return schema.default;
  }
  if (Array.isArray(schema.enum) && schema.enum.length) {
    return schema.enum[0];
  }
  if (schema.type === "object") {
    return Object.fromEntries(Object.entries(record(schema.properties)).map(([key, value]) => [key, previewExample(record(value), depth + 1)]));
  }
  if (schema.type === "array") {
    return Array.from({length: Math.min(Number(schema.minItems) || 0, 5)}, () => previewExample(record(schema.items), depth + 1));
  }
  if (schema.type === "boolean") {
    return false;
  }
  if (schema.type === "number" || schema.type === "integer") {
    return typeof schema.minimum === "number" ? schema.minimum : 0;
  }
  if (schema.type === "null") {
    return null;
  }
  return "测试内容";
}
