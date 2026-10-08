"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {useEffect, useSyncExternalStore} from "react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {IconCheck, IconDeviceDesktop, IconMoon, IconSun} from "@/components/ui/icons";
import {applyTheme, currentTheme, subscribeTheme, themeStorageKey} from "../lib/theme";

const choices = [{value: "light", label: "浅色", icon: IconSun}, {
  value: "dark",
  label: "深色",
  icon: IconMoon
}, {value: "system", label: "跟随系统", icon: IconDeviceDesktop}] as const;

export function ThemeInitializer() {
  useEffect(() => {
    let value: string | null = null;
    try {
      value = localStorage.getItem(themeStorageKey);
    } catch (failure) {
      console.warn("无法读取本机主题偏好，将跟随系统。", failure);
    }
    applyTheme(value === "light" || value === "dark" ? value : "system");
    const storage = (event: StorageEvent) => {
      if (event.key === themeStorageKey || event.key === null) {
        applyTheme(event.newValue === "light" || event.newValue === "dark" ? event.newValue : "system");
      }
    };
    window.addEventListener("storage", storage);
    return () => window.removeEventListener("storage", storage);
  }, []);
  return null;
}

export function ThemeMenu({className = "icon-button"}: { className?: string }) {
  const uiText = useT();
  const theme = useSyncExternalStore(subscribeTheme, currentTheme, () => "system" as const);
  const Icon = localizeCatalog(choices, uiText).find((value) => value.value === theme)!.icon;
  return <span className="theme-menu"><DropdownMenu>
    <DropdownMenuTrigger className={className} aria-label={uiText("外观与主题")}><Icon size={20}/></DropdownMenuTrigger>
    <DropdownMenuContent align="end" className="agenteam-menu agenteam-popup">
      {localizeCatalog(choices, uiText).map(({value, label, icon: ChoiceIcon}) => <DropdownMenuItem key={value}
                                                                                                    onClick={() => applyTheme(value)}><ChoiceIcon
        size={18}/>{label}{theme === value && <IconCheck size={16} className="theme-choice-check"/>}
      </DropdownMenuItem>)}
    </DropdownMenuContent>
  </DropdownMenu></span>;
}
