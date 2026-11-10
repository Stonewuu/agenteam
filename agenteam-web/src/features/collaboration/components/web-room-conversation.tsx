"use client";

import Link from "next/link";
import {useMemo, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {QueryState} from "@/components/ui/query-state";
import {IconArrowDown, IconArrowLeft, IconCollaboration, IconPlus} from "@/components/ui/icons";
import {ConversationMessageQueue} from "@/features/agent/components/conversation-message-queue";
import {ConversationComposerSurface} from "@/features/agent/components/conversation-composer-surface";
import {ComposerExtraTools} from "@/features/agent/components/composer-extra-tools";
import {RunQuestionCard} from "@/features/agent/components/run-question-card";
import type {ComposerSelectionContext} from "@/features/agent/lib/composer-selection";
import {useComposerLayout} from "@/features/agent/hooks/use-composer-layout";
import {useConversationScroll} from "@/features/agent/hooks/use-conversation-scroll";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {apiRequest} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {collaborationErrors} from "../types/collaboration";
import {collaborationHome, roomApi, roomRefreshInterval, type RoomDetail, type RoomEntry, type RoomMember} from "../types/web-room";
import {CollaborationStatus} from "./collaboration-status";
import {WebRoomMembers} from "./web-room-members";
import {WebRoomMessages} from "./web-room-messages";
import chat from "@/features/agent/components/agent-workspace.module.css";
import controls from "@/features/agent/components/conversation-controls.module.css";
import styles from "./web-room.module.css";

export function WebRoomConversation({enterprise, id, user}: {enterprise: string; id: string; user: string}) {
  const t = useT();
  const path = roomApi(enterprise) + "/rooms/" + encodeURIComponent(id);
  const query = useApiQuery<RoomDetail>(path, 0, 0, false, {refreshInterval: roomRefreshInterval, revalidateOnFocus: true});
  if (!query.data) {
    return <div className={styles.load}><QueryState {...query} hasData={false} empty={t("协作对话暂时无法读取。")}/></div>;
  }
  return <RoomThread enterprise={enterprise} scope={`room:${user}:${enterprise}:${id}`} path={path} detail={query.data}
    onRefresh={query.retry} readError={query.error}/>;
}

function RoomThread({enterprise, scope, path, detail, onRefresh, readError}: {
  enterprise: string; scope: string; path: string; detail: RoomDetail; onRefresh: () => void; readError: string;
}) {
  const t = useT();
  const task = detail.task;
  const action = useFormAction();
  const history = useFormAction();
  const queueAction = useFormAction();
  const [draft, setDraft] = useState("");
  const [fresh, setFresh] = useState(false);
  const [chosen, setChosen] = useState<string | null>(null);
  const [picker, setPicker] = useState<ComposerSelectionContext | null>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const [older, setOlder] = useState<RoomEntry[]>([]);
  const attempt = useRef<{signature: string; id: string} | null>(null);
  const start = !task || fresh && task.status === "completed" || ["cancelled", "failed"].includes(task.status) && (!chosen || chosen === task.ownerAgentId);
  const recipient = detail.members.find(member => member.agentId === (chosen ?? (start ? null : task?.ownerAgentId)));
  const uploads = useFileUploads(enterprise, "attachment", scope + ":" + (start ? "new" : task?.id));
  const acceptsFiles = recipient ? (start ? recipient.attachmentsEnabled : recipient.replyAttachmentsEnabled) : detail.members.some(member => member.attachmentsEnabled);
  const canSend = !action.busy && (!uploads.items.length || acceptsFiles && uploads.ready);
  const rows = useMemo(() => [...new Map([...older, ...detail.entries].map(entry => [entry.id, entry])).values()]
    .sort((a, b) => a.sequence - b.sequence), [older, detail.entries]);
  const items = useMemo(() => [...rows.map(entry => ({id: entry.id, content: entry.text})),
    ...detail.questions.map(question => ({id: question.id, content: question.question, status: question.status}))], [rows, detail.questions]);
  const {conversationRef, shouldStickToBottomRef, preserveHistoryPosition, awayFromBottom, hasUnseenContent, scrollToBottom} = useConversationScroll(scope, items);
  const dock = useComposerLayout();
  const first = rows[0]?.sequence;
  function choose(member: RoomMember) {
    const command = picker?.command;
    if (command && draft.slice(command.start, command.end) === command.text) {
      setDraft(draft.slice(0, command.start) + draft.slice(command.end));
      requestAnimationFrame(() => input.current?.setSelectionRange(command.start, command.start));
    }
    setChosen(member.agentId);
    input.current?.focus({preventScroll: true});
  }
  function changeText(value: string) {
    if (value.startsWith("@") || value.startsWith("＠")) {
      const addressed = value.slice(1);
      const matches = detail.members.filter(member => addressed.startsWith(member.name) && /\s/u.test(addressed[member.name.length] ?? ""));
      if (matches.length === 1) {
        setChosen(matches[0].agentId);
        setDraft(addressed.slice(matches[0].name.length).trimStart());
        setPicker(null);
        return;
      }
    }
    setDraft(value);
    if (picker?.command) {
      setPicker(null);
    }
  }
  async function send() {
    const text = draft.trim();
    if (!canSend || (!text && !uploads.fileIds.length)) {
      return;
    }
    if (!recipient) {
      setPicker({anchor: input});
      return;
    }
    const body = {text, recipientAgentId: chosen, attachmentIds: uploads.fileIds, intent: !task ? "mention" : start ? "start" : "continue", taskId: start ? null : task?.id,
      expectedInputRevision: start ? null : task?.inputRevision};
    const signature = JSON.stringify(body);
    if (attempt.current?.signature !== signature) {
      attempt.current = {signature, id: crypto.randomUUID()};
    }
    const clientMessageId = attempt.current.id;
    await action.execute(async () => {
      const accepted = await action.mutation.run<{status?: string}>(path + "/messages", {method: "POST", body: {...body, clientMessageId}});
      uploads.clear();
      setDraft("");
      setFresh(false);
      setChosen(accepted.status === "queued" ? recipient.agentId : null);
      setPicker(null);
      shouldStickToBottomRef.current = true;
      attempt.current = null;
      onRefresh();
    }, "");
  }
  async function changeQueue(id: string, prioritize: boolean) {
    await queueAction.execute(async () => {
      const target = path + "/queue/" + encodeURIComponent(id);
      await queueAction.mutation.run(prioritize ? target + "/prioritize" : target, {method: prioritize ? "POST" : "DELETE"});
    }, "");
    onRefresh();
  }

  const ownerName = detail.members.find(member => member.agentId === task?.ownerAgentId)?.name;
  const taskLabel = ownerName ? t("{0}的任务详情", [ownerName]) : t("任务详情");
  const otherMembersWorking = detail.activeTaskIds?.some(id => id !== task?.id);
  const taskUrl = task ? "/collaboration/tasks/" + encodeURIComponent(task.id) : null;
  return <div className={chat.shell}><div className={chat.main} data-conversation-main>
    <header className={chat.mainTopbar}><div className={controls.heading}><h1 title={detail.room.title}>{detail.room.title}</h1></div>
      <div className={styles.headerTools}>{task && taskUrl && <Link className={styles.taskLink} href={taskUrl} aria-label={taskLabel} title={taskLabel}>
        <CollaborationStatus status={task.status}/><span>{otherMembersWorking ? taskLabel : t("任务详情")}</span></Link>}
        <Link href={collaborationHome} className="icon-button" title={t("返回协作任务")} aria-label={t("返回协作任务")}><IconArrowLeft size={18}/></Link>
      </div></header>
    <div className={chat.threadRoot}>
      <section ref={conversationRef} className={chat.conversation} data-conversation-viewport aria-label={t("对话窗口")}>
        <div className={chat.messageList}>
          {first !== undefined && first > 1 && <Button className={controls.historyButton} type="button" disabled={history.busy}
            onClick={() => void history.execute(() => preserveHistoryPosition(async () => {
              const page = await apiRequest<RoomDetail>(path + "?before=" + first);
              setOlder(previous => [...page.entries, ...previous]);
            }), "")}>{t("查看更早消息")}</Button>}
          {history.error && <MutationFeedback action={history}/>}
          {!rows.length && <div className={controls.welcome}><span className={styles.welcomeIcon}><IconCollaboration size={30} variant="Bulk"/></span>
            <h2>{t("一起开始第一项任务")}</h2><p>{t("@ 一位成员，告诉它你想做什么。")}</p></div>}
          <WebRoomMessages rows={rows} enterprise={enterprise} activeTasks={detail.activeTaskIds ?? []}/>
          {detail.questions.map(question => <div key={question.id} data-conversation-message={question.id}>
            <RunQuestionCard enterprise={enterprise} question={question} onChanged={onRefresh}/></div>)}
          {task?.errorCode && <p className={controls.composerNotice}>{t(collaborationErrors[task.errorCode] ?? "当前任务需要处理，请查看任务详情。")}</p>}
          {readError && <div className={chat.loadError} role="alert"><p>{readError}</p><Button type="button" onClick={onRefresh}>{t("重新加载")}</Button></div>}
        </div>
      </section>
      {awayFromBottom && Boolean(items.length) && <Button type="button" className={chat.scrollToBottom} onClick={scrollToBottom}
        aria-label={t(hasUnseenContent ? "有新内容，回到底部" : "回到底部")} title={t("回到底部")}>
        <IconArrowDown size={18}/>{hasUnseenContent && <span>{t("有新内容")}</span>}
      </Button>}
      <span className={controls.actionAnnouncement} role="status">{hasUnseenContent ? t("有新内容，可以回到底部查看。") : ""}</span>
      <div ref={dock} className={chat.composerWrap}>
        {action.error && <MutationFeedback action={action} onReload={onRefresh}/>}
        {recipient && !acceptsFiles && <p className={controls.composerNotice}>{t("这位成员未启用接收附件。")}</p>}
        {queueAction.error && <MutationFeedback action={queueAction} onReload={onRefresh}/>}
        <ConversationMessageQueue items={(detail.queuedMessages ?? []).map(item => ({id: item.id, group: item.agentId,
          recipient: item.recipientName, text: item.text, selected: item.selected}))} onChange={changeQueue}/>
        <ConversationComposerSurface text={draft} onText={changeText} inputRef={input}
          onCommand={({marker, context}) => {
            if (marker === "@") {
              setPicker(context);
            }
          }} onSubmit={() => void send()} canSubmit={canSend}
          submitting={action.busy} files={uploads} acceptsFiles={acceptsFiles} maxLength={32000}
          placeholder={t(start && !recipient ? "输入 @ 选择成员，或点击下方选择" : "输入消息，@ 可切换成员…")}
          mainTools={<><WebRoomMembers members={detail.members} recipient={recipient} context={picker} input={input} onOpen={setPicker}
            onClose={() => setPicker(null)} onPick={choose} disabled={action.busy}/>
            <ComposerExtraTools files={uploads} attachmentsEnabled={acceptsFiles} disabled={action.busy}/></>}
          endTools={task?.status === "completed" && <Button className={styles.newTask} type="button" aria-pressed={fresh}
            disabled={action.busy} onClick={() => {
              setFresh(value => !value);
              setChosen(null);
              setPicker(null);
            }}>
            <IconPlus size={15}/>{t(fresh ? "返回继续当前任务" : "开启新任务")}</Button>}/>
      </div>
    </div>
  </div></div>;
}
