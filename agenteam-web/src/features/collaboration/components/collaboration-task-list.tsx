"use client";

import Link from "next/link";
import {useRouter} from "next/navigation";
import {useId, useLayoutEffect, useRef} from "react";
import {Button} from "@/components/ui/button";
import {Guidance} from "@/components/ui/guidance";
import {IconArrowRight, IconCollaboration, IconRefresh, IconUsers} from "@/components/ui/icons";
import {PageHeader} from "@/components/ui/page-header";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {SearchInput} from "@/components/ui/search-input";
import {Select} from "@/components/ui/select";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useT} from "@/lib/i18n/locale-provider";
import {useCollaborationList} from "../hooks/use-collaboration-list";
import {taskStatusFilters} from "../lib/collaboration-list-state";
import {collaborationStatus, type CollaborationDirectoryEntry} from "../types/collaboration";
import {collaborationHome, newRoomUrl, roomUrl} from "../types/web-room";
import {WebRoomCreate} from "./web-room-create";
import {CollaborationStatus} from "./collaboration-status";
import ui from "@/components/ui/surface.module.css";
import styles from "./collaboration-list.module.css";

export function CollaborationTaskList({enterpriseId, userId, create}: {enterpriseId: string; userId: string; create: boolean}) {
  const router = useRouter();
  const t = useT();
  const list = useCollaborationList(enterpriseId, userId);
  const container = useRef<HTMLDivElement>(null);
  const restored = useRef("");
  const positionKey = JSON.stringify([enterpriseId, userId, list.state.query, list.state.status, list.state.cursor]);
  const position = list.state.scrollTop;
  useLayoutEffect(() => {
    const element = container.current?.closest<HTMLElement>("[data-page-scroll]");
    if (!element || !list.data || list.loading || restored.current === positionKey) {
      return;
    }
    let frame = 0;
    let done = false;
    const restore = () => {
      if (done) {
        return;
      }
      const available = Math.max(0, element.scrollHeight - element.clientHeight);
      element.scrollTop = Math.min(position, available);
      element.dispatchEvent(new Event("page-scroll-restored"));
      if (available >= position) {
        done = true;
        restored.current = positionKey;
      }
    };
    const stop = () => {
      done = true;
      restored.current = positionKey;
    };
    const resize = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(restore);
    });
    resize.observe(container.current!);
    frame = requestAnimationFrame(restore);
    element.addEventListener("wheel", stop, {passive: true});
    element.addEventListener("touchstart", stop, {passive: true});
    element.addEventListener("keydown", stop);
    return () => {
      cancelAnimationFrame(frame);
      resize.disconnect();
      element.removeEventListener("wheel", stop);
      element.removeEventListener("touchstart", stop);
      element.removeEventListener("keydown", stop);
    };
  }, [list.data, list.loading, position, positionKey]);
  return <div ref={container} className={styles.page} onClickCapture={event => {
    if (event.target instanceof Element && event.target.closest("a[href]")) {
      list.saveScroll(container.current?.closest<HTMLElement>("[data-page-scroll]")?.scrollTop ?? 0);
    }
  }}>
    <PageHeader title={t("协作任务")} icon={<IconCollaboration size={24} variant="Bulk"/>}
      description={t("查看任务进展、协作成果和岗位对话。")}
      actions={<><Link className={ui.primary} href={newRoomUrl} scroll={false}><IconCollaboration size={17}/>{t("新建协作对话")}</Link>
        <Button className={ui.button} disabled={list.loading} onClick={list.refresh}><IconRefresh size={16}/>{t("刷新")}</Button></>}/>
    <div className={styles.toolbar} role="search" aria-label={t("查找协作任务")}>
      <SearchInput className={styles.search} value={list.state.query} maxLength={100} placeholder={t("搜索任务、对话或编号")}
        aria-label={t("搜索任务、对话或编号")} onChange={event => list.criteria({query: event.target.value})} onClear={() => list.criteria({query: ""})}/>
      <Select className={styles.statusFilter} aria-label={t("任务状态")} value={list.state.status} onChange={event => list.criteria({status: event.target.value})}>
        <option value="">{t("全部状态")}</option>
        {taskStatusFilters.map(status => <option key={status} value={status}>{t(collaborationStatus[status])}</option>)}
      </Select>
      {list.filtered && <Button className={ui.button} onClick={list.clear}>{t("清除筛选")}</Button>}
    </div>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={t("没有找到匹配的任务。")}
      emptyContent={list.filtered ? <div className={styles.empty}><IconCollaboration size={28}/><p>{t("没有找到匹配的任务。")}</p>
        <Button className={ui.button} onClick={list.clear}>{t("清除筛选")}</Button></div>
        : <Guidance title={t("从聊天开始协作")} icon={<IconCollaboration size={20}/>}>
          <p>{t("可以在网页邀请智能体协作，也可以在已接入的群聊中 @ 机器人发起任务。")}</p>
        </Guidance>}>
      <ul className={styles.list} aria-label={t("协作任务列表")}>
        {list.data?.items.map(entry => <TaskRow key={entry.task ? "task:" + entry.task.id : "room:" + entry.room.id} entry={entry}/>)}
      </ul>
    </QueryState>
    {list.error && list.state.cursor && <Button className={`${ui.button} ${styles.reloadFirst}`} onClick={list.first}>{t("从第一页重新加载")}</Button>}
    <Pagination {...list} hasMore={list.data?.hasMore}/>
    {create && <WebRoomCreate enterprise={enterpriseId} onClose={() => router.replace(collaborationHome, {scroll: false})}/>}
  </div>;
}

function TaskRow({entry}: {entry: CollaborationDirectoryEntry}) {
  const t = useT();
  const titleId = useId();
  const {task, room} = entry;
  const titlePath = room ? roomUrl(room.id) : collaborationHome + "/" + encodeURIComponent(task.id);
  const platform = task?.source?.platformCode;
  const roomId = room?.id ?? (platform === "web" ? task?.conversationId : null);
  const chatPath = roomId ? roomUrl(roomId) : task?.primaryConversationId ? "/conversations/" + encodeURIComponent(task.primaryConversationId) : null;
  const title = room?.title ?? (task?.title || task?.shortCode || "");
  const platformName = platform === "feishu" ? t("飞书") : platform === "wecom_aibot" ? t("企业微信") : "";
  const source = roomId ? [t("网页协作对话"), task?.source?.name].filter(Boolean).join(" · ") : task?.source ? task.source.kind === "direct" ? t("机器人私聊") : task.source.name || t("群聊") : "";
  const roles = (room?.members ?? task?.roles?.map(role => role.name))?.join("、");
  const updatedAt = room?.updatedAt ?? task?.updatedAt;
  const nextStep = task?.status === "waiting_input" ? t("补充信息") : task?.status === "waiting_approval" ? t("查看审批") : null;
  const actionPath = nextStep ? titlePath : chatPath ?? titlePath;
  const actionLabel = nextStep ?? (chatPath ? t("进入对话") : t("任务详情"));
  const sourceName = [platformName, source].filter(Boolean).join(" · ");
  return <li className={styles.row}>
    <span className={styles.icon} data-icon-surface aria-hidden="true"><IconCollaboration size={22} variant="Bulk"/></span>
    <h2 className={styles.heading} id={titleId}>
      <Link className={styles.title} href={titlePath} title={title} aria-description={t(room ? "进入对话" : "任务详情")}>{title}</Link>
    </h2>
    <div className={styles.meta}>
      <CollaborationStatus status={task?.status ?? "not_started"}/>
      {task && <span className={styles.code}>{task.shortCode}</span>}
      {chatPath && nextStep ? <Link className={styles.sourceLink} href={chatPath} title={t("进入对话")}>{sourceName || t("进入对话")}</Link>
        : sourceName && <span className={styles.source} title={sourceName}>{sourceName}</span>}
      {roles && (!room || roles !== room.title) && <span className={styles.roles} title={roles}><IconUsers size={14}/><span>{roles}</span></span>}
      {updatedAt && <span className={styles.updated} title={t("最近更新")}><EnterpriseDateTime value={updatedAt}/></span>}
    </div>
    <Link className={styles.rowAction} href={actionPath} data-attention={Boolean(nextStep)} aria-describedby={titleId}>
      {actionLabel}<IconArrowRight size={16}/>
    </Link>
  </li>;
}
