import type {PluginTool} from "@/features/plugin/types/plugin";

export type ParameterSchema = Record<string, unknown>;
export type VariableOption = {path: string; label: string; group?: string; schema?: ParameterSchema};

export function parameterObject(value: unknown): ParameterSchema {
  return value && typeof value === "object" && !Array.isArray(value) ? value as ParameterSchema : {};
}

/** 只展开本地结构引用；未知或递归结构保留原始编辑入口。 */
export function parameterSchema(value: unknown, root: ParameterSchema = parameterObject(value), depth = 0): ParameterSchema {
  const schema = parameterObject(value);
  if (depth > 6) {
    return schema;
  }
  if (typeof schema.$ref === "string" && schema.$ref.startsWith("#/")) {
    const target = schema.$ref.slice(2).split("/").reduce<unknown>((part, key) => parameterObject(part)[key.replace(/~1/g, "/").replace(/~0/g, "~")], root);
    if (target) {
      const siblings = {...schema};
      delete siblings.$ref;
      return {...parameterSchema(target, root, depth + 1), ...siblings};
    }
  }
  if (Array.isArray(schema.allOf)) {
    const merged = schema.allOf.reduce<ParameterSchema>((result, part) => {
      const next = parameterSchema(part, root, depth + 1);
      return {...result, ...next, properties: {...parameterObject(result.properties), ...parameterObject(next.properties)},
        required: [...new Set([...(Array.isArray(result.required) ? result.required : []), ...(Array.isArray(next.required) ? next.required : [])])]};
    }, {});
    return {...merged, ...schema, properties: {...parameterObject(merged.properties), ...parameterObject(schema.properties)},
      required: [...new Set([...(Array.isArray(merged.required) ? merged.required : []), ...(Array.isArray(schema.required) ? schema.required : [])])]};
  }
  return schema;
}

export function parameterType(schema: ParameterSchema): string {
  const type = Array.isArray(schema.type) ? schema.type.find((item) => item !== "null") : schema.type;
  if (typeof type === "string") {
    return type;
  }
  if (schema.properties) {
    return "object";
  }
  return "";
}

export function parameterChoices(schema: ParameterSchema): {value: unknown; label: string}[] {
  if (Array.isArray(schema.enum)) {
    return schema.enum.map((value) => ({value, label: String(value)}));
  }
  const variants = schema.oneOf ?? schema.anyOf;
  if (Array.isArray(variants) && variants.every((item) => Object.hasOwn(parameterObject(item), "const"))) {
    return variants.map((item) => ({value: item.const, label: String(item.title ?? item.const)}));
  }
  return [];
}

export function parameterDefault(raw: unknown, root = parameterObject(raw), depth = 0): unknown {
  const schema = parameterSchema(raw, root);
  if (Object.hasOwn(schema, "default")) {
    return structuredClone(schema.default);
  }
  if (Object.hasOwn(schema, "const")) {
    return structuredClone(schema.const);
  }
  if (parameterType(schema) === "object" && depth < 6) {
    const defaults = Object.entries(parameterObject(schema.properties)).flatMap(([name, field]) => {
      const value = parameterDefault(field, root, depth + 1);
      return value === undefined ? [] : [[name, value]];
    });
    return defaults.length ? Object.fromEntries(defaults) : undefined;
  }
  return undefined;
}

/** 切换能力只补充明确默认值；已有参数继续显示，交由用户处理，不静默丢弃。 */
export function parameterDefaults(schema: ParameterSchema, previous: Record<string, unknown>) {
  return {...parameterObject(parameterDefault(schema)), ...previous};
}

export function selectedWorkflowTool(tools: PluginTool[], config: Record<string, unknown>) {
  if (config.toolId) {
    return tools.find((tool) => (tool.entryId ?? tool.id) === config.toolId);
  }
  const matches = tools.filter((tool) => tool.name === config.toolName);
  return matches.length === 1 ? matches[0] : undefined;
}

export function referencePath(value: unknown) {
  return typeof value === "string" ? /^\$\{([^{}]+)}$/.exec(value)?.[1] ?? "" : "";
}

export function referenceValue(path: string) {
  return `\${${path}}`;
}

export function uniqueParameterName(value: Record<string, unknown>, suggested = "value") {
  const base = suggested.split(".").at(-1)?.replace(/[^A-Za-z0-9_-]/g, "") || "value";
  let name = base, index = 2;
  while (Object.hasOwn(value, name)) {
    name = `${base}${index++}`;
  }
  return name;
}
