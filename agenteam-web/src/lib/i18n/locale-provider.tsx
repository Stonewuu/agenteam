"use client";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState} from "react";
import {defaultLocale, isLocale, type Locale, localeCookie, locales, localeStorageKey} from "./locales";
import {createTranslator} from "./translate";

type LocaleContextValue = { locale: Locale; setLocale: (locale: Locale) => void };
const LocaleContext = createContext<LocaleContextValue>({
  locale: defaultLocale, setLocale: () => {
  }
});

function persistLocale(locale: Locale) {
  document.documentElement.lang = locale;
  document.documentElement.dir = locales[locale].direction;
  document.title = createTranslator(locale)("群策 AgenTeam · 智能体工作平台");
  document.cookie = `${localeCookie}=${encodeURIComponent(locale)}; Path=/; Max-Age=31536000; SameSite=Lax${location.protocol === "https:" ? "; Secure" : ""}`;
  try {
    localStorage.setItem(localeStorageKey, locale);
  } catch (failure) {
    console.warn("无法保存本机界面语言，本次切换仍然生效。", failure);
  }
}

export function LocaleProvider({initialLocale, children}: { initialLocale: Locale; children: ReactNode }) {
  const [locale, updateLocale] = useState(initialLocale);
  const setLocale = useCallback((next: Locale) => {
    if (isLocale(next)) {
      persistLocale(next);
      updateLocale(next);
    }
  }, []);
  useEffect(() => {
    const storage = (event: StorageEvent) => {
      if (event.key === localeStorageKey && isLocale(event.newValue)) {
        document.documentElement.lang = event.newValue;
        document.documentElement.dir = locales[event.newValue].direction;
        document.title = createTranslator(event.newValue)("群策 AgenTeam · 智能体工作平台");
        updateLocale(event.newValue);
      }
    };
    window.addEventListener("storage", storage);
    return () => window.removeEventListener("storage", storage);
  }, []);
  const value = useMemo(() => ({locale, setLocale}), [locale, setLocale]);
  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale() {
  return useContext(LocaleContext);
}

export function useT() {
  const {locale} = useLocale();
  return useMemo(() => createTranslator(locale), [locale]);
}
