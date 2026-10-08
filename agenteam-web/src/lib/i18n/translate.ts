import {en} from "./messages/en";
import {type Locale, locales} from "./locales";

type MessageValue = string | number | boolean | null | undefined;
export type MessageValues = readonly MessageValue[] | Readonly<Record<string, MessageValue>>;
export type Translator = ((message: string, values?: MessageValues) => string) & {
  locale: Locale;
  formatLocale: string
};
const dictionaries: Record<Locale, Readonly<Record<string, string>>> = {"zh-CN": {}, en};

/** 只翻译代码明确标记的界面文案，不扫描或改写用户内容。 */
export function translate(locale: Locale, message: string, values?: MessageValues): string {
  const dictionary = dictionaries[locale];
  const text = Object.hasOwn(dictionary, message) ? dictionary[message] : message;
  if (!values) {
    return text;
  }
  return text.replace(/\{(\w+)\}/g, (placeholder, key: string) => {
    const value = (values as Readonly<Record<string, MessageValue>>)[key];
    return Object.hasOwn(values, key) ? String(value ?? "") : placeholder;
  });
}

export function createTranslator(locale: Locale): Translator {
  return Object.assign((message: string, values?: MessageValues) => translate(locale, message, values), {
    locale, formatLocale: locales[locale].formatLocale,
  });
}

const localizedCatalogs = new WeakMap<object, Map<Locale, unknown>>();

/** 静态菜单和字段目录按语言复用引用，避免切换语言时重新创建业务状态。 */
export function localizeCatalog<T>(source: T, t: Translator): T {
  if (typeof source === "string") {
    return t(source) as T;
  }
  if (source === null || typeof source !== "object") {
    return source;
  }
  if (!Array.isArray(source) && Object.getPrototypeOf(source) !== Object.prototype && Object.getPrototypeOf(source) !== null
    || "$$typeof" in source) {
    return source;
  }
  const cached = localizedCatalogs.get(source);
  if (cached?.has(t.locale)) {
    return cached.get(t.locale) as T;
  }
  const translated = Array.isArray(source) ? source.map((value) => localizeCatalog(value, t))
    : Object.fromEntries(Object.entries(source).map(([key, value]) => [key, localizeCatalog(value, t)]));
  const versions = cached ?? new Map<Locale, unknown>();
  versions.set(t.locale, translated);
  localizedCatalogs.set(source, versions);
  return translated as T;
}
