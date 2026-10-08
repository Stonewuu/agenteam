"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {usePathname} from "next/navigation";
import {Button} from "@/components/ui/button";
import {IconChevronRight} from "@/components/ui/icons";
import {BrandMark} from "@/components/ui/brand-mark";
import {capabilityNavigation, managementNavigation, workspaceNavigation} from "../lib/navigation";
import homeStyles from "./home-page.module.css";
import styles from "./workspace-loading.module.css";

type LoadingLayout = "home" | "conversation" | "settings" | "list";

function loadingPage(pathname: string) {
  const [, , , section, detail] = pathname.split("/");
  if (pathname.startsWith("/settings")) {
    return {title: "个人设置", area: "工作空间", layout: "settings" as const};
  }
  if (section === "capabilities") {
    return {
      title: capabilityNavigation.find((item) => item.path === detail)?.label ?? "能力中心",
      area: "能力中心",
      layout: "list" as const
    };
  }
  if (section === "management") {
    return {
      title: managementNavigation.find((item) => item.id === detail)?.label ?? "企业概览",
      area: "管理端",
      layout: "list" as const
    };
  }
  if (section === "conversations" || section === "new-task") {
    return {title: section === "new-task" ? "新对话" : "对话任务", area: "工作空间", layout: "conversation" as const};
  }
  const title = workspaceNavigation.find((item) => item.path === section)?.label
    ?? ({notifications: "通知与公告", memories: "个人偏好记忆"}[section] ?? "工作台");
  return {title, area: "工作空间", layout: !section || section === "workspace" ? "home" as const : "list" as const};
}

function LoadingRows({count = 3}: { count?: number }) {
  return <div className={styles.rows}>
    {Array.from({length: count}, (_, index) => <div className={styles.row} key={index}>
      <span className={styles.square}/>
      <div className={styles.copy}><span className={styles.line}/><span className={`${styles.line} ${styles.short}`}/>
      </div>
    </div>)}
  </div>;
}

export function WorkspacePageLoading({layout, embedded = false, showHeading = true}: {
  layout?: LoadingLayout;
  embedded?: boolean;
  showHeading?: boolean;
}) {
  const uiText = useT();
  const pathname = usePathname() ?? "/";
  const page = localizeCatalog(loadingPage(pathname), uiText);
  const currentLayout = layout ?? page.layout;
  const listOnly = /\/conversations\/?$/.test(pathname);
  return <div
    className={`${styles.page} ${embedded ? styles.embedded : currentLayout === "home" ? "page-content" : ""}`}
    data-workspace-page-loading={currentLayout}
    data-list-only={listOnly} role="status" aria-label={uiText("正在加载{0}…", [uiText(page.title)])} aria-busy="true">
    <div className={styles.visual} aria-hidden="true">
      {currentLayout === "conversation" ?
        <div className={`${styles.conversation} ${embedded ? styles.conversationEmbedded : ""}`}>
          {!embedded && <div className={styles.conversationList}>
            <span className={`${styles.line} ${styles.titleLine}`}/>
            <div className={styles.search}/>
            <LoadingRows count={5}/>
          </div>}
          <div className={styles.conversationMain}>
            <span className={`${styles.line} ${styles.titleLine}`}/>
            <div className={styles.messageLines}><span className={styles.line}/><span className={styles.line}/><span
              className={`${styles.line} ${styles.short}`}/></div>
            <div className={styles.composer}><span className={`${styles.line} ${styles.short}`}/><span
              className={styles.smallPill}/></div>
          </div>
        </div> : <div className={`${styles.content} ${currentLayout === "home" ? homeStyles.home : ""}`}>
          {showHeading && <div className={styles.heading}><span className={`${styles.line} ${styles.titleLine}`}/><span
            className={`${styles.line} ${styles.short}`}/></div>}
          {currentLayout === "home" ? <>
            <div className={styles.homeComposer}><span className={styles.line}/><span className={styles.smallPill}/>
            </div>
            <div className={styles.homeSections}><LoadingRows/><LoadingRows/></div>
          </> : currentLayout === "settings" ? <div className={styles.settings}>
            <LoadingRows count={4}/>
            <div className={styles.form}>
              {[0, 1, 2].map((index) => <div className={styles.field} key={index}><span
                className={`${styles.line} ${styles.short}`}/>
                <div className={styles.input}/>
              </div>)}
            </div>
          </div> : <>
            <div className={styles.search}/>
            <LoadingRows count={4}/></>}
        </div>}
    </div>
  </div>;
}

export function WorkspaceLoading({error, onRetry}: { error?: string; onRetry?: () => void }) {
  const uiText = useT();
  const page = localizeCatalog(loadingPage(usePathname() ?? "/"), uiText);
  return <div className={`app-shell ${styles.shell}`} data-workspace-loading aria-busy={!error}>
    <aside className="sidebar" aria-hidden="true">
      <div className="brand">
        <span className="brand-mark"><BrandMark size={32}/></span>
        <span className="brand-copy"><span
          className="brand-title">AgenTeam</span><small>{uiText("群策 · 智能体工作平台")}</small></span>
      </div>
      <div className={styles.navigation}><LoadingRows count={5}/></div>
      <div className={styles.navigationBottom}><LoadingRows count={2}/></div>
    </aside>
    <div className="main-shell">
      <header className="topbar" aria-hidden="true">
        <div className="breadcrumbs"><span>{page.area}</span><IconChevronRight
          size={13}/><strong>{uiText(page.title)}</strong></div>
        <div className={styles.account}><span className={styles.smallPill}/><span className={styles.square}/></div>
      </header>
      <main className={`workspace-scroll-region${page.layout === "conversation" ? " workspace-full-height" : ""}`}>
        {error ? <section className={styles.error}>
          <h1>{uiText("暂时无法打开")}{page.title}</h1>
          <p role="alert">{localizeUiMessage(error ?? "", uiText)}</p>
          {onRetry && <Button className="button" type="button" onClick={onRetry}>{uiText("重新加载")}</Button>}
        </section> : <WorkspacePageLoading/>}
      </main>
    </div>
  </div>;
}
