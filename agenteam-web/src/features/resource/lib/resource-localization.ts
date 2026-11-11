import type {Translator} from "@/lib/i18n/translate";
import builtinCatalog from "@/lib/i18n/generated/builtin-plugins.json";

type ResourceText = {source?: string; builtinCode?: string | null; name: string; description?: string};
type LocalizedText = {source: string; "zh-CN": string; en: string};
const builtins: Readonly<Record<string, {name: LocalizedText; description: LocalizedText}>> = builtinCatalog;

function displayText(resource: ResourceText, field: "name" | "description", t: Translator): string {
  const original = resource[field] ?? "";
  if (resource.source !== "builtin") {
    return original;
  }
  const descriptor = resource.builtinCode && Object.hasOwn(builtins, resource.builtinCode)
    ? builtins[resource.builtinCode][field] : null;
  return descriptor && original === descriptor.source ? descriptor[t.locale] : t(original);
}

/** 只翻译平台内置内容，用户创建或导入的同名资源保留原文。 */
export function resourceDisplayName(resource: ResourceText, t: Translator): string {
  return displayText(resource, "name", t);
}

export function resourceDisplayDescription(resource: ResourceText, t: Translator): string {
  return displayText(resource, "description", t);
}
