"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {memo, type Ref, type RefObject, useEffect, useImperativeHandle, useRef} from "react";
import {Button} from "@/components/ui/button";
import {Dialog} from "@/components/ui/dialog";
import {IconMessages, IconX} from "@/components/ui/icons";
import {useSidebarResize} from "../hooks/use-sidebar-resize";
import {useSidebarPanels} from "../hooks/use-sidebar-panels";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/shadcn/tabs";
import type {ConversationSidebarTab} from "../types/conversation-sidebar";
import {conversationSidebarTabs} from "./conversation-sidebar-tabs";
import styles from "./conversation-sidebar.module.css";

export type ConversationSidebarHandle = { openTab: (tab: ConversationSidebarTab) => void };

export const ConversationSidebar = memo(function ConversationSidebar({
                                                                       id,
                                                                       enterprise,
                                                                       conversation,
                                                                       open,
                                                                       compact,
                                                                       tab,
                                                                       onTabChange,
                                                                       onClose,
                                                                       running,
                                                                       refreshKey,
                                                                       width,
                                                                       maximumWidth,
                                                                       onWidthChange,
                                                                       onResizeEnd,
                                                                       container,
                                                                       onRestoreConversation,
                                                                       tabs = conversationSidebarTabs,
                                                                       ref
                                                                     }: {
  id: string;
  enterprise: string;
  conversation: string | null;
  open: boolean;
  compact: boolean;
  tab: string;
  onTabChange: (tab: string) => void;
  onClose: () => void;
  running: boolean;
  refreshKey: string;
  tabs?: readonly ConversationSidebarTab[];
  width: number;
  maximumWidth: number;
  onWidthChange: (width: number) => void;
  onResizeEnd: (width: number) => void;
  container: RefObject<HTMLDivElement | null>;
  onRestoreConversation: () => void;
  ref?: Ref<ConversationSidebarHandle>;
}) {
  const uiText = useT();
  const scope = enterprise + ":" + conversation;
  const panels = useSidebarPanels(scope, tabs, tab, onTabChange);
  useImperativeHandle(ref, () => ({openTab: panels.open}), [panels.open]);
  const contents = useRef<HTMLDivElement>(null);
  const resize = useSidebarResize(container, width, maximumWidth, open && !compact, onResizeEnd);
  const selected = panels.selected;
  useEffect(() => {
    if (open) {
      contents.current?.querySelector<HTMLElement>("[data-sidebar-tab][aria-selected=true]")?.scrollIntoView({
        block: "nearest",
        inline: "nearest"
      });
    }
  }, [selected, open, compact]);
  const closeTab = (tabId: string) => {
    const next = panels.close(tabId);
    requestAnimationFrame(() => {
      const trigger = Array.from(contents.current?.querySelectorAll<HTMLElement>("[data-sidebar-tab]") ?? [])
        .find((item) => item.dataset.sidebarTab === next);
      trigger?.focus();
    });
  };
  const body = <div className={styles.contents} id={id} ref={contents}>
    <Tabs className={styles.tabs} value={selected} onValueChange={(value) => onTabChange(String(value))}>
      <div className={styles.heading}>
        {!compact && <Button className={styles.restoreConversation} type="button" title={uiText("重新展开对话区域")}
                             onClick={onRestoreConversation}><IconMessages size={18}/>{uiText("展开对话")}</Button>}
        <TabsList className={styles.tabList} variant="line" aria-label={uiText("对话侧栏标签")}>
          {panels.tabs.map((item) => <div className={styles.tabItem} key={item.id}
                                          data-closable={item.closable || undefined}>
            <TabsTrigger className={styles.tab} value={item.id} data-sidebar-tab={item.id}
                         title={item.title ?? (item.localizeLabel ? uiText(item.label) : item.label)}
                         onKeyDown={(event) => {
                           if (item.closable && event.key === "Delete") {
                             event.preventDefault();
                             closeTab(item.id);
                           }
                         }}>
              <item.icon size={17}/>
              <span>{item.localizeLabel ? uiText(item.label) : item.label}</span></TabsTrigger>
            {item.closable &&
              <Button type="button" className={styles.closeTab} aria-label={uiText("关闭预览 ") + item.label}
                      title={uiText("关闭预览")}
                      tabIndex={selected === item.id ? 0 : -1} onClick={() => closeTab(item.id)}><IconX
                size={14}/></Button>}
          </div>)}
        </TabsList>
        {!compact && <span className={styles.toggleSpace} aria-hidden="true"/>}
      </div>
      {panels.tabs.map((item) => <TabsContent className={styles.panel} key={scope + ":" + item.id} value={item.id}
                                              keepMounted>
        <item.panel enterprise={enterprise} conversation={conversation} active={open && selected === item.id}
                    running={running} refreshKey={refreshKey}
                    state={panels.states[item.id]} onStateChange={(state) => panels.update(item.id, state)}
                    onOpenTab={panels.open}/>
      </TabsContent>)}
    </Tabs>
  </div>;
  if (compact) {
    return open ? <Dialog title={uiText("对话文件")} drawer onClose={onClose}
                          bodyClassName={styles.mobileBody}>{body}</Dialog> : null;
  }
  return <aside className={styles.sidebar} data-open={open} aria-label={uiText("对话侧栏")} aria-hidden={!open}
                inert={!open}>
    <div className={styles.resizeHandle} role="separator" aria-label={uiText("调整对话侧栏宽度")}
         aria-orientation="vertical" tabIndex={open ? 0 : -1}
         aria-valuemin={280} aria-valuemax={maximumWidth} aria-valuenow={width} aria-valuetext={width + uiText(" 像素")}
         title={uiText("拖动调整侧栏宽度")} {...resize}
         onKeyDown={(event) => {
           const next = event.key === "ArrowLeft" ? width + 16 : event.key === "ArrowRight" ? Math.min(width - 16, maximumWidth - Math.max(400, maximumWidth * 0.2))
             : event.key === "Home" ? 280 : event.key === "End" ? maximumWidth : null;
           if (next !== null) {
             event.preventDefault();
             onWidthChange(next);
           }
         }}/>
    {body}
  </aside>;
});
