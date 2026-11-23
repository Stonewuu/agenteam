"use client";

import {useT} from "@/lib/i18n/locale-provider";

import type {ReactNode} from "react";
import {ConversationProjectControl} from "@/features/project/components/conversation-project-control";
import type {useConversationProject} from "@/features/project/hooks/use-conversation-project";
import styles from "./composer-project-frame.module.css";

/** 首页与触屏常驻显示项目栏，桌面对话页在输入区上沿按需滑出，减少对正文空间的占用。 */
export function ComposerProjectFrame({state, children, alwaysShowProject = false}: {
  state: ReturnType<typeof useConversationProject>;
  children: ReactNode;
  alwaysShowProject?: boolean
}) {
  const uiText = useT();
  if (!state.visible) {
    return children;
  }
  const hasError = Boolean(state.error);
  return <div className={styles.frame} data-always-visible={alwaysShowProject || undefined}>
    <div className={styles.projectHover} data-saving={state.saving || undefined}
         data-error={hasError || undefined}
         data-always-visible={alwaysShowProject || undefined} role="group" aria-label={uiText("项目")}>
      <div className={styles.project} data-composer-part="project"><ConversationProjectControl state={state}
                                                                                               showError={false}
                                                                                               inlineReload={hasError}/></div>
    </div>
    <div className={styles.surface}>{children}</div>
  </div>;
}
