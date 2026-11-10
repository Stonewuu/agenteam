"use client";

import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "@/components/ui/shadcn/collapsible";
import {IconArrowsJoin, IconChevronDown, IconCollaboration} from "@/components/ui/icons";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {ConversationUserMessage} from "@/features/agent/components/conversation-user-message";
import {ConversationReplyAuthor, CopyReplyButton} from "@/features/agent/components/conversation-reply-parts";
import {ConversationMarkdown} from "@/features/agent/components/conversation-message-blocks";
import {useT} from "@/lib/i18n/locale-provider";
import {groupRoomEntries} from "../lib/web-room-timeline";
import type {RoomEntry} from "../types/web-room";
import chat from "@/features/agent/components/agent-workspace.module.css";
import reply from "@/features/agent/components/assistant-message.module.css";
import controls from "@/features/agent/components/conversation-controls.module.css";
import styles from "./web-room.module.css";

const labels: Record<string, string> = {assigned: "委派工作", handed_off: "移交工作", work_completed: "提交成果",
  completed: "回复", waiting_dependency: "等待协作成果", waiting_input: "等待补充要求", waiting_approval: "等待确认操作",
  started: "开始处理", cancelled: "已停止"};
const progressKinds = new Set(["started", "waiting_dependency"]);

function RoomProgress({entries, result}: {entries: RoomEntry[]; result?: RoomEntry}) {
  const t = useT();
  if (!entries.length && !result?.text) {
    return null;
  }
  return <Collapsible className={styles.progressGroup}>
    <div className={styles.turnActions}>
      {entries.length > 0 && <CollapsibleTrigger className={styles.progressTrigger}>
        <IconArrowsJoin size={15}/><span>{t("协作进展")}</span><IconChevronDown className={styles.disclosure} size={13}/>
      </CollapsibleTrigger>}
      {result?.text && <div className={`${controls.messageActions} ${styles.replyActions}`} role="group" aria-label={t("回复操作")}>
        <CopyReplyButton text={result.text} messageId={result.id}/></div>}
    </div>
    {entries.length > 0 && <CollapsibleContent className={styles.progressPanel}>
      <ol className={styles.progressList}>{entries.map(entry => <li key={entry.id}>{t(labels[entry.kind])}</li>)}</ol>
    </CollapsibleContent>}
  </Collapsible>;
}

export function WebRoomMessages({rows, enterprise, activeTasks}: {rows: RoomEntry[]; enterprise: string; activeTasks: string[]}) {
  const t = useT();
  return groupRoomEntries(rows).map(turn => {
    const first = turn[0];
    const last = turn[turn.length - 1];
    if (first.kind === "user" || first.kind === "unprocessed") {
      return <article key={first.id} className={`${chat.messageRow} ${chat.userMessageRow}`} data-conversation-message={first.id}>
        <ConversationUserMessage enterprise={enterprise} message={{status: first.kind === "unprocessed" ? "cancelled" : "completed", content: [first.recipientName && "@" + first.recipientName, first.text].filter(Boolean).join(" "), attachments: first.attachments ?? [], blocks: [], createdAt: first.createdAt}}/>
      </article>;
    }
    const progress = turn.filter(entry => progressKinds.has(entry.kind));
    const content = turn.filter(entry => !progressKinds.has(entry.kind) && entry.text);
    const result = last.kind === "completed" || last.kind === "work_completed";
    const live = activeTasks.includes(last.taskId) && rows.findLast(row => row.taskId === last.taskId) === last;
    const activity = live ? last : content.at(-1) ?? last;
    const avatar = first.authorIcon ? <ResourceAvatar icon={first.authorIcon} color={first.authorColor ?? undefined} size="small"/>
      : <IconCollaboration size={19}/>;
    return <article key={first.id} className={`${chat.messageRow} ${chat.assistantMessageRow}`} data-conversation-message={first.id}>
      <div className={reply.assistantMessage}>
        <ConversationReplyAuthor avatar={avatar} name={first.authorName} createdAt={first.createdAt}>
          {activity.kind !== "completed" && <small className={styles.entryLabel}>
            {t(live && activity.kind === "started" ? "正在处理" : labels[activity.kind] ?? "协作进展")}
          </small>}
        </ConversationReplyAuthor>
        <div className={`${controls.messageBody} ${styles.turnBody}`}>
          {content.map(entry => <ConversationMarkdown key={entry.id} content={entry.text} mode="static" allowImages={false}/>)}
          <RoomProgress entries={progress} result={result ? last : undefined}/>
        </div>
      </div>
    </article>;
  });
}
