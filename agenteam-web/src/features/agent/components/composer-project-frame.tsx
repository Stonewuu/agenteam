"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import type {ReactNode} from "react";
import {Button} from "@/components/ui/button";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {ConversationProjectControl} from "@/features/project/components/conversation-project-control";
import type {useConversationProject} from "@/features/project/hooks/use-conversation-project";
import styles from "./composer-project-frame.module.css";

/** 预留展开高度，让项目卡片滑出时不遮挡消息或移动输入位置。 */
export function ComposerProjectFrame({state, children, alwaysShowProject = false}: {
  state: ReturnType<typeof useConversationProject>;
  children: ReactNode;
  alwaysShowProject?: boolean
}) {
  const uiText = useT();
  if (!state.visible) {
    return children;
  }
  return <div className={styles.frame}>
    <div className={styles.projectHover} data-saving={state.saving || undefined}
         data-always-visible={alwaysShowProject || undefined} role="group" aria-label={uiText("项目")}>
      <div className={styles.project} data-composer-part="project"><ConversationProjectControl state={state}
                                                                                               showError={false}/></div>
    </div>
    <div className={styles.surface}>{children}</div>
    <AnimatedHeight>{state.error &&
      <p className={styles.error} role="alert">{localizeUiMessage(state.error ?? "", uiText)}
        <Button type="button" onClick={state.reload}>{uiText("重新加载")}</Button>
      </p>}</AnimatedHeight>
  </div>;
}
