"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {type ReactNode, useId, useLayoutEffect, useRef} from "react";
import {usePathname} from "next/navigation";
import {IconPencil, IconSidebarLeft} from "@/components/ui/icons";
import {useConversationNavigation} from "./conversation-navigation-context";
import {ConversationRail} from "./conversation-rail";
import styles from "./conversation-frame.module.css";

export function ConversationFrame({children}: { children: ReactNode }) {
  const uiText = useT();
  const navigation = useConversationNavigation();
  const pathname = usePathname();
  const listId = useId();
  const aside = useRef<HTMLElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const actions = useRef<HTMLDivElement>(null);
  const mobile = navigation?.mobile ?? false;
  const rootPage = pathname.endsWith("/conversations");
  const showList = mobile && (rootPage || Boolean(navigation?.mobileListOpen));
  const collapsed = !mobile && Boolean(navigation?.collapsed);
  const listVisible = mobile ? showList : !collapsed;
  const canToggle = !(mobile && rootPage);
  const canCreate = navigation?.context.permissions.includes("agent.run") ?? false;

  useLayoutEffect(() => {
    const hiddenPanel = !listVisible ? aside.current : mobile ? content.current : null;
    const hiddenNewAction = mobile && !showList && actions.current?.querySelector("[data-new-conversation]");
    if (hiddenPanel?.contains(document.activeElement) || hiddenNewAction === document.activeElement) {
      actions.current?.querySelector<HTMLButtonElement>("button")?.focus({preventScroll: true});
    }
  }, [listVisible, mobile, showList]);

  if (!navigation) {
    return children;
  }

  function closeMobileList() {
    if (mobile) {
      actions.current?.querySelector<HTMLButtonElement>("button")?.focus({preventScroll: true});
    }
    navigation!.setMobileListOpen(false);
  }

  const toggleLabel = mobile ? showList ? uiText("返回当前对话") : uiText("查看对话列表") : collapsed ? uiText("展开对话列表") : uiText("收起对话列表");
  return <div className={styles.frame} data-conversation-frame data-collapsed={collapsed} data-show-list={showList}
              data-can-create={canCreate} data-can-toggle={canToggle}
              onKeyDown={(event) => {
                if (event.key === "Escape" && !event.defaultPrevented && mobile && showList && !rootPage && event.currentTarget.contains(event.target as Node)) {
                  event.preventDefault();
                  event.stopPropagation();
                  closeMobileList();
                }
              }}>
    <div ref={actions} className={styles.listActions} role="group" aria-label={uiText("对话列表操作")}>
      {canToggle && <Button className={`icon-button ${styles.listToggle}`} type="button" aria-label={toggleLabel}
                            title={toggleLabel}
                            aria-expanded={listVisible} aria-controls={listId}
                            onClick={() => mobile ? showList ? closeMobileList() : navigation.setMobileListOpen(true) : navigation.setCollapsed(!collapsed)}>
        <IconSidebarLeft size={20} variant="Linear"/>
      </Button>}
      {canCreate &&
        <Button type="button" data-new-conversation className={styles.newConversation} aria-label={uiText("新建对话")}
                title={uiText("新建对话")} inert={mobile && !showList} aria-hidden={mobile && !showList}
                onClick={() => {
                  closeMobileList();
                  navigation.startNewTask();
                }}><IconPencil size={18}/><span>{uiText("新建对话")}</span></Button>}
    </div>
    <aside ref={aside} id={listId} className={styles.frameAside} aria-label={uiText("对话列表")} inert={!listVisible}
           aria-hidden={!listVisible}>
      <ConversationRail onNavigate={closeMobileList}/>
    </aside>
    <div ref={content} className={styles.frameContent} inert={showList} aria-hidden={showList}>{children}</div>
  </div>;
}
