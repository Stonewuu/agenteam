import type {Metadata} from "next";
import type {ReactNode} from "react";
import {NavigationGuard} from "@/features/workspace/components/navigation-guard";
import {ThemeInitializer} from "@/features/auth/components/theme-menu";
import {Toaster} from "@/components/ui/toast";
import {LocaleProvider} from "@/lib/i18n/locale-provider";
import {requestLocale} from "@/lib/i18n/server";
import {createTranslator} from "@/lib/i18n/translate";
import {locales} from "@/lib/i18n/locales";
import "./globals.css";

export async function generateMetadata(): Promise<Metadata> {
  const locale = await requestLocale();
  const t = createTranslator(locale);
  return {
    title: t("群策 AgenTeam · 智能体工作平台"),
    description: t("群策 AgenTeam 智能体工作平台"),
    icons: {icon: "/icon.svg"},
  };
}

export default async function RootLayout({children}: { children: ReactNode }) {
  const locale = await requestLocale();
  return (
    <html lang={locale} dir={locales[locale].direction} suppressHydrationWarning>
    <head>
      <script
        dangerouslySetInnerHTML={{__html: `try{var t=localStorage.getItem('agenteam:theme')||'system',d=t==='dark'||(t!=='light'&&matchMedia('(prefers-color-scheme: dark)').matches);document.documentElement.dataset.theme=d?'dark':'light';document.documentElement.dataset.themePreference=t;document.documentElement.classList.toggle('dark',d);document.documentElement.style.colorScheme=d?'dark':'light'}catch{}`}}/>
    </head>
    <body><LocaleProvider
      initialLocale={locale}><ThemeInitializer/><NavigationGuard>{children}</NavigationGuard><Toaster/></LocaleProvider>
    </body>
    </html>
  );
}
