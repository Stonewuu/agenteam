"use client";

import Link from "next/link";
import {useLayoutEffect, useRef} from "react";
import {Button} from "@/components/ui/button";
import {Guidance} from "@/components/ui/guidance";
import {IconArrowRight, IconCollaboration, IconListDetails, IconMessages, IconRefresh, IconUsers} from "@/components/ui/icons";
import {PageHeader} from "@/components/ui/page-header";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {SearchInput} from "@/components/ui/search-input";
import {Select} from "@/components/ui/select";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useT} from "@/lib/i18n/locale-provider";
import {useCollaborationList} from "../hooks/use-collaboration-list";
import {taskStatusFilters} from "../lib/collaboration-list-state";
import {collaborationStatus, type CollaborationTask} from "../types/collaboration";
import {CollaborationStatus} from "./collaboration-status";
import ui from "@/components/ui/surface.module.css";
import styles from "./collaboration-list.module.css";

export function CollaborationTaskList({enterpriseId, userId}: {enterpriseId: string; userId: string}) {
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
      actions={<Button className={ui.button} disabled={list.loading} onClick={list.refresh}><IconRefresh size={16}/>{t("刷新")}</Button>}/>
    <div className={styles.toolbar} role="search" aria-label={t("查找协作任务")}>
      <SearchInput className={styles.search} value={list.state.query} maxLength={100} placeholder={t("搜索任务或编号")}
        aria-label={t("搜索任务或编号")} onChange={event => list.criteria({query: event.target.value})} onClear={() => list.criteria({query: ""})}/>
      <Select className={styles.statusFilter} aria-label={t("任务状态")} value={list.state.status} onChange={event => list.criteria({status: event.target.value})}>
        <option value="">{t("全部状态")}</option>
        {taskStatusFilters.map(status => <option key={status} value={status}>{t(collaborationStatus[status])}</option>)}
      </Select>
      {list.filtered && <Button className={ui.button} onClick={list.clear}>{t("清除筛选")}</Button>}
    </div>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={t("没有找到匹配的任务。")}
      emptyContent={list.filtered ? <div className={styles.empty}><IconCollaboration size={28}/><p>{t("没有找到匹配的任务。")}</p>
        <Button className={ui.button} onClick={list.clear}>{t("清除筛选")}</Button></div>
        : <Guidance title={t("从聊天开始协作")} icon={<IconCollaboration size={20}/>} actions={
          <Link className={ui.button} href="/settings?tab=channels">{t("企业账号绑定")}<IconArrowRight size={16}/></Link>}>
          <p>{t("在飞书或企微中向机器人发送需求；在群聊中，请先 @ 机器人。任务进展和成果会显示在这里。")}</p>
        </Guidance>}>
      <ul className={styles.list} aria-label={t("协作任务列表")}>
        {list.data?.items.map(task => <TaskRow key={task.id} task={task} enterprise={enterpriseId}/>)}
      </ul>
    </QueryState>
    {list.error && list.state.cursor && <Button className={`${ui.button} ${styles.reloadFirst}`} onClick={list.first}>{t("从第一页重新加载")}</Button>}
    <Pagination {...list} hasMore={list.data?.hasMore}/>
  </div>;
}

function TaskRow({task, enterprise}: {task: CollaborationTask; enterprise: string}) {
  const t = useT();
  const root = `/enterprises/${encodeURIComponent(enterprise)}`;
  const detailPath = `${root}/collaboration/tasks/${encodeURIComponent(task.id)}`;
  const platform = task.source?.platformCode === "feishu" ? t("飞书") : task.source?.platformCode === "wecom_aibot" ? t("企业微信") : "";
  const source = task.source ? task.source.kind === "direct" ? t("机器人私聊") : task.source.name || t("群聊") : "";
  const roles = task.roles?.map(role => role.name).join("、");
  const needsInput = task.status === "waiting_input";
  return <li className={styles.row}>
    <div className={styles.identity}>
      <span className={styles.icon} data-icon-surface aria-hidden="true"><IconCollaboration size={22} variant="Bulk"/></span>
      <div className={styles.copy}>
        <h2><Link className={styles.title} href={detailPath} title={task.title}>{task.title || task.shortCode}</Link></h2>
        <div className={styles.meta}>
          <span className={styles.code}>{task.shortCode}</span>
          {source && <span className={styles.source} title={[platform, source].filter(Boolean).join(" · ")}>{[platform, source].filter(Boolean).join(" · ")}</span>}
          {roles && <span className={styles.roles} title={roles}><IconUsers size={14}/><span>{roles}</span></span>}
        </div>
      </div>
    </div>
    <div className={styles.state}><CollaborationStatus status={task.status}/>
      <span className={styles.updated} title={t("最近更新")}><EnterpriseDateTime value={task.updatedAt}/></span>
    </div>
    <div className={styles.actions}>
      {task.primaryConversationId && <Link className={ui.button} href={`${root}/conversations/${encodeURIComponent(task.primaryConversationId)}`}>
        <IconMessages size={16}/>{t("查看对话")}</Link>}
      <Link className={needsInput ? ui.primary : ui.button} href={detailPath}>
        <IconListDetails size={16}/>{t(needsInput ? "补充信息" : "任务详情")}</Link>
    </div>
  </li>;
}
