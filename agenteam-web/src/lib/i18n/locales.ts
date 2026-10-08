export const locales = {
  "zh-CN": {name: "简体中文", formatLocale: "zh-CN", direction: "ltr"},
  en: {name: "English", formatLocale: "en-US", direction: "ltr"},
} as const;

export type Locale = keyof typeof locales;
export const defaultLocale: Locale = "zh-CN";
export const localeCookie = "agenteam-locale";
export const localeStorageKey = "agenteam:locale";

export function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && Object.hasOwn(locales, value);
}

export function detectLocale(header: string | null): Locale {
  const candidates = (header ?? "").split(",").map((entry, index) => {
    const [language, quality] = entry.trim().split(";");
    const weight = quality?.startsWith("q=") ? Number(quality.slice(2)) : 1;
    return {language, weight: Number.isFinite(weight) ? weight : 0, index};
  }).filter((entry) => entry.weight > 0).sort((left, right) => right.weight - left.weight || left.index - right.index);
  for (const {language} of candidates) {
    if (isLocale(language)) {
      return language;
    }
    const match = Object.keys(locales).find((locale) => locale.split("-")[0] === language.toLowerCase().split("-")[0]);
    if (match && isLocale(match)) {
      return match;
    }
  }
  return defaultLocale;
}
