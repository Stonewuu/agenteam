"use client";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {IconLanguage} from "@/components/ui/icons";
import {useLocale, useT} from "@/lib/i18n/locale-provider";
import {isLocale, locales} from "@/lib/i18n/locales";

export function LanguageMenu({className = "icon-button"}: { className?: string }) {
  const {locale, setLocale} = useLocale();
  const t = useT();
  return <DropdownMenu>
    <DropdownMenuTrigger className={className} aria-label={t("界面语言：{0}", [locales[locale].name])}
                         title={t("切换界面语言")}>
      <IconLanguage size={20}/>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" className="agenteam-menu agenteam-popup">
      <DropdownMenuRadioGroup value={locale} aria-label={t("界面语言")} onValueChange={(value) => {
        if (isLocale(value)) {
          setLocale(value);
        }
      }}>
        {Object.entries(locales).map(([code, language]) => <DropdownMenuRadioItem value={code} key={code} closeOnClick>
          <span lang={code}>{language.name}</span>
        </DropdownMenuRadioItem>)}
      </DropdownMenuRadioGroup>
    </DropdownMenuContent>
  </DropdownMenu>;
}
