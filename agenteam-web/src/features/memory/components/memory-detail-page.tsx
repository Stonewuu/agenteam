"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {ResourceAvatar} from "@/components/ui/resource-avatar";

import {toast} from "@/components/ui/toast";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import Link from "next/link";
import {useState} from "react";
import {useRouter} from "next/navigation";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {MemoryEditor} from "./memory-editor";
import {MemoryDeleteDialog} from "./memory-delete-dialog";
import type {Memory, MemoryContext} from "../types/memory";
import ui from "@/components/ui/surface.module.css";
import styles from "./memory.module.css";

export function MemoryDetailPage({enterpriseId, agentId}: { enterpriseId: string; agentId: string }) {
  return <EnterpriseGate enterpriseId={enterpriseId}>{({user, context}) => <MemoryDetail
    key={`${enterpriseId}:${agentId}`} user={user} context={context} agentId={agentId}/>}</EnterpriseGate>;
}

function MemoryDetail({user, context, agentId}: { user: IdentityUser; context: EnterpriseContext; agentId: string }) {
  const uiText = useT();
  const enterpriseId = context.enterprise.id;
  const router = useRouter();
  const root = organizationPath(enterpriseId, `/agents/${encodeURIComponent(agentId)}/memories`);
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState<Memory | "create" | null>(null);
  const [deleting, setDeleting] = useState<Memory | "all" | null>(null);
  const detail = useApiQuery<MemoryContext>(`${root}/context`, `${refresh}:${user.preferences.revision}`);
  const list = useApiPage<Memory>(root, refresh, 0);
  const changed = (message = "") => {
    toast.success(message);
    list.first();
    setRefresh((value) => value + 1);
  };
  const reload = () => {
    setEditing(null);
    setDeleting(null);
    changed();
  };
  return <PlatformShell user={user} context={context} area="user" title={detail.data?.agentName ?? uiText("个人偏好")}>
    <div className={styles.toolbar}><Link className={ui.button}
                                          href={enterprisePath(enterpriseId, "/memories")}>{uiText("← 已保存的偏好")}</Link><Button
      className={ui.button} disabled={detail.loading || list.loading}
      onClick={() => changed()}>{uiText("刷新")}</Button></div>
    <QueryState {...detail} hasData={Boolean(detail.data)}
                empty={uiText("暂时无法查看该员工的偏好。")}>{detail.data && <>
      <PageHeader title={uiText("{0}的偏好", [detail.data.agentName])}
                  leading={<ResourceAvatar icon={detail.data.agentIcon ?? ""}
                                           color={detail.data.agentColor ?? undefined}/>}
                  actions={<>{detail.data.canSave &&
                    <Button className={ui.primary} onClick={() => setEditing("create")}>{uiText("保存偏好")}</Button>}
                    {Boolean(list.data?.items.length) && <Button className={ui.danger}
                                                                 onClick={() => setDeleting("all")}>{uiText("清空偏好")}</Button>}</>}/>
      {detail.data.unavailableReason &&
        <p className={ui.notice}>{uiText(detail.data.unavailableReason)}{!detail.data.enabled && <> <Link
          href="/settings">{uiText("打开个人设置")}</Link></>}</p>}
      <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("尚未保存偏好。")}>
        <div className={styles.list}>{list.data?.items.map((memory) => <article key={memory.id} className={styles.card}>
          <h2>{memory.memoryKey}</h2><p className={styles.body}>{memory.content}</p><p
          className={styles.time}>{uiText("更新于 ")}<EnterpriseDateTime
          value={memory.updatedAt}/><br/>{uiText("有效至 ")}<EnterpriseDateTime value={memory.expiresAt}/></p>
          <div className={ui.actions}><Button className={ui.button}
                                              onClick={() => setEditing(memory)}>{uiText("编辑")}</Button><Button
            className={ui.danger} onClick={() => setDeleting(memory)}>{uiText("删除")}</Button></div>
        </article>)}</div>
      </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
    </>}</QueryState>
    {editing &&
      <MemoryEditor enterpriseId={enterpriseId} agentId={agentId} initial={editing === "create" ? null : editing}
                    onClose={() => setEditing(null)} onReload={reload} onSaved={() => {
        setEditing(null);
        changed(uiText("偏好已保存。"));
      }}/>}
    {deleting && detail.data &&
      <MemoryDeleteDialog enterpriseId={enterpriseId} agentId={agentId} agentName={detail.data.agentName}
                          memory={deleting === "all" ? null : deleting} onClose={() => setDeleting(null)}
                          onReload={reload} onDeleted={() => {
        setDeleting(null);
        toast.success(uiText("偏好已删除。"));
        if (deleting === "all" || list.data?.items.length === 1 && !detail.data?.canSave) {
          router.replace(enterprisePath(enterpriseId, "/memories"));
        } else {
          changed();
        }
      }}/>}
  </PlatformShell>;
}
