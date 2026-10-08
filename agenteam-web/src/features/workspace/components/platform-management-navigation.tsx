"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {usePathname} from "next/navigation";
import {platformManagementNavigation} from "../lib/navigation";
import {NavigationLink} from "./navigation-link";

/** 平台配置不携带企业编号，只对系统超级管理员呈现。 */
export function PlatformManagementNavigation({capabilities}: {capabilities: readonly string[]}) {
  const uiText = useT();
  const pathname = usePathname();
  return <div className="platform-management-navigation">
    <div className="sidebar-separator" role="separator"/>
    <p className="nav-caption">{uiText("平台管理")}</p>
    {localizeCatalog(platformManagementNavigation, uiText).filter((item) => !item.capability || capabilities.includes(item.capability)).map(({path, label, icon: Icon}) => <NavigationLink key={path}
                                                                                                              label={label}
                                                                                                              href={path}
                                                                                                              className={`nav-item${pathname === path ? " selected" : ""}`}
                                                                                                              aria-current={pathname === path ? "page" : undefined}>
      <Icon size={20} variant="Bulk"/><span>{label}</span>
    </NavigationLink>)}
  </div>;
}
