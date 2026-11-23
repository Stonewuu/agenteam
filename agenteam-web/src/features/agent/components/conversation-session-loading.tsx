"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";
import {useT} from "@/lib/i18n/locale-provider";
import {Button} from "@/components/ui/button";
import {IconAlertCircle, IconRefresh} from "@/components/ui/icons";
import workspaceStyles from "./agent-workspace.module.css";
import styles from "./conversation-session-loading.module.css";

/** 对话切换与首次进入时，按真实消息版式呈现柔和的骨架占位，避免整片空白和纯文字加载提示。 */
export function ConversationSessionLoading() {
  const uiText = useT();
  return <div className={styles.thread} data-loading-placeholder role="status" aria-busy="true"
              aria-label={uiText("正在加载会话消息…")}>
    <div className={styles.userTurn} aria-hidden="true">
      <div className={styles.userMeta}>
        <span className={`${styles.shimmer} ${styles.timeLine}`}/>
        <span className={`${styles.shimmer} ${styles.avatar}`}/>
      </div>
      <div className={styles.userBubble}>
        <span className={`${styles.shimmer} ${styles.line} ${styles.userLineLong}`}/>
        <span className={`${styles.shimmer} ${styles.line} ${styles.userLineShort}`}/>
      </div>
    </div>

    <div className={styles.assistantTurn} aria-hidden="true">
      <div className={styles.assistantMeta}>
        <span className={`${styles.shimmer} ${styles.avatar}`}/>
        <span className={`${styles.shimmer} ${styles.nameLine}`}/>
        <span className={`${styles.shimmer} ${styles.timeLine}`}/>
      </div>
      <div className={styles.assistantBody}>
        <span className={`${styles.shimmer} ${styles.line} ${styles.line92}`}/>
        <span className={`${styles.shimmer} ${styles.line} ${styles.line80}`}/>
        <span className={`${styles.shimmer} ${styles.line} ${styles.line58}`}/>
        <div className={styles.detailCard}>
          <div className={styles.detailHeader}>
            <span className={`${styles.shimmer} ${styles.cardIcon}`}/>
            <span className={`${styles.shimmer} ${styles.cardTitle}`}/>
            <span className={`${styles.shimmer} ${styles.cardStatus}`}/>
          </div>
          <div className={styles.detailLines}>
            <span className={`${styles.shimmer} ${styles.line} ${styles.line86}`}/>
            <span className={`${styles.shimmer} ${styles.line} ${styles.line64}`}/>
          </div>
        </div>
        <span className={`${styles.shimmer} ${styles.line} ${styles.line84}`}/>
        <span className={`${styles.shimmer} ${styles.line} ${styles.line46}`}/>
      </div>
    </div>

    <div className={`${styles.userTurn} ${styles.secondaryTurn}`} aria-hidden="true">
      <div className={styles.userBubbleCompact}>
        <span className={`${styles.shimmer} ${styles.line} ${styles.userLineCompact}`}/>
      </div>
    </div>

    <div className={`${styles.assistantTurn} ${styles.secondaryTurn}`} aria-hidden="true">
      <div className={styles.assistantMeta}>
        <span className={`${styles.shimmer} ${styles.avatar}`}/>
        <span className={`${styles.shimmer} ${styles.nameLine}`}/>
      </div>
      <div className={styles.assistantBody}>
        <span className={`${styles.shimmer} ${styles.line} ${styles.line76}`}/>
        <span className={`${styles.shimmer} ${styles.line} ${styles.line52}`}/>
      </div>
    </div>
  </div>;
}

/** 已有对话加载期间同步占位悬浮输入框，加载完成后在原位平滑切换为真实输入区。 */
export function ConversationComposerSkeleton() {
  return <div className={workspaceStyles.composerWrap} aria-hidden="true">
    <div className={`${workspaceStyles.composer} ${styles.composerSkeleton}`}>
      <div className={styles.composerLines}>
        <span className={`${styles.shimmer} ${styles.composerPlaceholder}`}/>
      </div>
      <div className={styles.composerBar}>
        <div className={styles.composerLeft}>
          <span className={`${styles.shimmer} ${styles.employeePill}`}/>
          <span className={`${styles.shimmer} ${styles.toolCircle}`}/>
        </div>
        <div className={styles.composerRight}>
          <span className={`${styles.shimmer} ${styles.controlPill}`}/>
          <span className={`${styles.shimmer} ${styles.controlPillShort}`}/>
          <span className={`${styles.shimmer} ${styles.sendCircle}`}/>
        </div>
      </div>
    </div>
  </div>;
}

/** 会话初次加载失败时的居中提示卡片，保留明确错误说明与重试入口。 */
export function ConversationLoadError({error, onReload}: { error: string; onReload: () => void }) {
  const uiText = useT();
  return <div className={styles.errorCard} role="alert">
    <span className={styles.errorIcon} aria-hidden="true">
      <IconAlertCircle size={20} data-icon-context="inherit"/>
    </span>
    <div className={styles.errorCopy}>
      <p>{localizeUiMessage(error, uiText)}</p>
    </div>
    <Button type="button" className="button secondary small" onClick={onReload}>
      <IconRefresh size={15}/>
      <span>{uiText("重新加载")}</span>
    </Button>
  </div>;
}
