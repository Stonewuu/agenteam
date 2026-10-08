"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import Link from "next/link";
import {usePathname} from "next/navigation";
import {createContext, type ReactNode, useContext, useState} from "react";
import {enterprisePath, landingPath} from "@/features/auth/lib/identity-navigation";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {ThemeMenu} from "@/features/auth/components/theme-menu";
import {LanguageMenu} from "@/features/auth/components/language-menu";
import {BrandMark} from "@/components/ui/brand-mark";
import {Dialog} from "@/components/ui/dialog";
import {PageHeaderIconProvider} from "@/components/ui/page-header";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {
  IconArrowUpRight,
  IconBell,
  IconBookmark,
  IconChevronRight,
  IconLayoutGrid,
  IconMenu2,
  IconSettings,
  IconShield
} from "@/components/ui/icons";
import {useGuardedNavigation} from "./navigation-guard";
import {NotificationLink} from "@/features/notification/components/notification-link";
import {NotificationCenterProvider} from "@/features/notification/components/notification-center-provider";
import {WorkspaceSearch} from "./workspace-search";
import {ReturnToWorkspace} from "./return-to-workspace";
import {AccountMenu} from "./account-menu";
import {EnterpriseMenu} from "./enterprise-menu";
import {
  canViewManagementSection,
  capabilityNavigation,
  managementNavigation,
  platformManagementNavigation,
  workspaceNavigation
} from "../lib/navigation";
import {PlatformManagementNavigation} from "./platform-management-navigation";
import {SidebarNavigation} from "./sidebar-navigation";
import {NavigationLink} from "./navigation-link";
import {usePageScroll} from "../hooks/use-page-scroll";
import {ConversationNavigationProvider} from "@/features/agent/components/conversation-navigation-context";
import {ConversationFrame} from "@/features/agent/components/conversation-frame";
import {ComposerHandoffProvider} from "@/features/agent/components/composer-handoff-context";

type PlatformShellProps = {
  user: IdentityUser;
  context: EnterpriseContext | null;
  area: "user" | "capabilities" | "management";
  title: string;
  children: ReactNode;
  onNavigate?: (action: () => void) => void;
  fullHeight?: boolean;
};
const ExistingShell = createContext(false);

export function PlatformShell(props: PlatformShellProps) {
  const nested = useContext(ExistingShell);
  return nested ? props.children : <PersistentShell {...props} />;
}

function PersistentShell(props: PlatformShellProps) {
  if (!props.context) {
    return <ShellFrame {...props} />;
  }
  return <NotificationCenterProvider key={`${props.user.id}:${props.context.enterprise.id}`}
                                     enterpriseId={props.context.enterprise.id}
                                     permissions={props.context.permissions} showAlerts={props.area === "user"}>
    <ComposerHandoffProvider><ConversationNavigationProvider
      context={props.context}><ShellFrame {...props} /></ConversationNavigationProvider></ComposerHandoffProvider>
  </NotificationCenterProvider>;
}

function ShellFrame({user, context, area, title, children, onNavigate, fullHeight = false}: PlatformShellProps) {
  const uiText = useT();
  const pathname = usePathname();
  const platformPage = pathname.startsWith("/management/");
  const [mobileNavigation, setMobileNavigation] = useState({pathname, open: false});
  if (mobileNavigation.pathname !== pathname) {
    setMobileNavigation({pathname, open: false});
  }
  const mobile = mobileNavigation.open;
  const setMobile = (open: boolean) => setMobileNavigation({pathname, open});
  const {container} = usePageScroll(pathname, !fullHeight);
  const guarded = useGuardedNavigation();
  const root = context ? enterprisePath(context.enterprise.id) : "";
  const allowed = (permission: string) => Boolean(context?.permissions.includes(permission));
  const go = (action: () => void) => (onNavigate ?? guarded ?? ((action: () => void) => action()))(action);
  const areaName = area === "capabilities" ? uiText("能力中心") : area === "management" ? uiText("管理端") : uiText("工作空间");
  const items = area === "management" ? localizeCatalog(managementNavigation, uiText).filter((item) => canViewManagementSection(item, context?.permissions ?? [], context?.capabilities ?? []) && !("hidden" in item && item.hidden)).map((item) => ({
      ...item,
      path: `management/${item.id}`
    }))
    : area === "capabilities" ? localizeCatalog(capabilityNavigation, uiText).filter((item) => allowed(`${item.permission}.view`) || allowed(`${item.permission}.create`)).map((item) => ({
        ...item,
        path: `capabilities/${item.path}`
      }))
      : localizeCatalog(workspaceNavigation, uiText).filter((item) => item.permissions.some(allowed));
  const section = pathname.slice(root.length).split("/")[1];
  const chatArea = area === "user" && (section === "conversations" || section === "new-task") && allowed("conversation.view");
  const navigationPath = pathname.replace(/\/management\/invitations$/, "/management/members").replace(/\/management\/permissions$/, "/management/roles");
  const isActive = (path: string, index: number) => navigationPath === `${root}/${path}` || navigationPath.startsWith(`${root}/${path}/`) || path === "conversations" && section === "new-task"
    || area !== "user" && pathname === `${root}/${area}` && index === 0;
  const selectedIndex = items.findIndex((item, index) => isActive(item.path, index));
  const pageSection = pathname.slice(root.length).split("/")[2];
  const PageIcon = localizeCatalog(platformManagementNavigation, uiText).find((item) => item.path === pathname)?.icon ?? (pathname.startsWith("/settings") ? IconSettings : section === "notifications" ? IconBell : section === "memories" ? IconBookmark
    : area === "management" ? localizeCatalog(managementNavigation, uiText).find((item) => item.id === pageSection)?.icon ?? items[selectedIndex]?.icon ?? IconShield
      : items[selectedIndex]?.icon ?? IconLayoutGrid);
  const switchSuffix = area === "management" ? "/management" : area === "capabilities" ? "/capabilities" : `/` + (["workspace", "conversations", "employees", "schedules", "todos", "notifications", "memories"].includes(section) ? section : "workspace");
  const navigation = <>
    <Link className="brand" href={context ? `${root}/workspace` : landingPath(user)}
          aria-label={uiText("AgenTeam 工作台")}>
      <span className="brand-mark"><BrandMark size={32}/></span>
      <span className="brand-copy"><span
        className="brand-title">AgenTeam</span><small>{uiText("群策 · 智能体工作平台")}</small></span>
    </Link>
    <div className={`return-slot${area !== "user" ? " has-return" : ""}`} aria-hidden={area === "user"}
         inert={area === "user"}>
      <div>{context ? <ReturnToWorkspace context={context} userId={user.id} className="return-user"/> :
        <Link href={landingPath(user)} className="return-user">{uiText("返回工作空间")}</Link>}</div>
    </div>
    <p className="nav-caption">{area === "management" ? uiText("企业管理") : areaName}</p>
    <SidebarNavigation label={uiText("{0}主导航", [areaName])}>{items.map(({path, label, icon: Icon}, index) => {
      const active = isActive(path, index);
      return <div key={path} className="nav-group">
        <div className="nav-group-row"><NavigationLink label={label} href={`${root}/${path}`}
                                                       className={`nav-item${active ? " selected" : ""}`}
                                                       aria-current={active ? "page" : undefined}><Icon size={20}
                                                                                                        variant="Bulk"/><span>{label}</span></NavigationLink>
        </div>
      </div>;
    })}{area === "management" && user.superAdmin && <PlatformManagementNavigation capabilities={user.capabilities}/>}</SidebarNavigation>
    <div className="sidebar-bottom">
      <div className="sidebar-separator"/>
      {allowed("capabilities.view") &&
        <NavigationLink label={uiText("能力中心")} className={`nav-item${area === "capabilities" ? " selected" : ""}`}
                        href={`${root}/capabilities`}><IconLayoutGrid size={20}
                                                                      variant="Bulk"/><span>{uiText("能力中心")}</span><IconArrowUpRight
          size={15}/></NavigationLink>}
      {allowed("admin.view") &&
        <NavigationLink label={uiText("管理端")} className={`nav-item${area === "management" ? " selected" : ""}`}
                        href={`${root}/management`}><IconShield size={20}
                                                                variant="Bulk"/><span>{uiText("管理端")}</span><IconArrowUpRight
          size={15}/></NavigationLink>}
    </div>
  </>;
  return <div className="app-shell">
    <a className="skip-link" href="#main-content">{uiText("跳到页面内容")}</a>
    <aside className="sidebar">{navigation}</aside>
    {mobile && <Dialog title={areaName} onClose={() => setMobile(false)} drawer>
      <div className="mobile-sidebar">{navigation}</div>
    </Dialog>}
    <div className="main-shell">
      <header className="topbar">
        <div className="breadcrumbs"><Button className="icon-button mobile-menu" aria-label={uiText("打开导航")}
                                             onClick={() => setMobile(true)}><IconMenu2
          size={22}/></Button><span>{areaName}</span><IconChevronRight size={13}/><strong title={title}>{title}</strong>
        </div>
        <div className="topbar-actions">{context && !platformPage && <><WorkspaceSearch key={context.permissionVersion}
                                                                                        enterpriseId={context.enterprise.id}
                                                                                        permissions={context.permissions}
                                                                                        className="global-search"/><EnterpriseMenu
          user={user} context={context} suffix={switchSuffix} go={go}/>
          <div className="topbar-divider"/>
        </>}<LanguageMenu/><ThemeMenu className="icon-button theme-trigger"/>{context && !platformPage &&
          <NotificationLink enterpriseId={context.enterprise.id}
                            className="icon-button notification-trigger"/>}<AccountMenu user={user} go={go}/></div>
      </header>
      <main ref={container} id="main-content" data-page-scroll={fullHeight ? undefined : ""}
            className={`workspace-scroll-region${fullHeight ? " workspace-full-height" : ""}`}>
        <div className={fullHeight ? "workspace-viewport" : "page-content"}><ExistingShell.Provider key={user.id} value><PageHeaderIconProvider
          icon={<PageIcon size={26} variant="Bulk"/>}>{chatArea ? <ConversationFrame>{children}</ConversationFrame> :
          <LoadingTransition>{children}</LoadingTransition>}</PageHeaderIconProvider></ExistingShell.Provider></div>
      </main>
    </div>
  </div>;
}
