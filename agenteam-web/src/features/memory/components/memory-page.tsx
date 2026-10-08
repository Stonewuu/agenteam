"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import {useState} from "react";
import Link from "next/link";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useApiPage} from "@/lib/http/use-api-query";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {SearchInput} from "@/components/ui/search-input";
import {IconChevronRight} from "@/components/ui/icons";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import type {MemoryAgent} from "../types/memory";
import ui from "@/components/ui/surface.module.css";
import styles from "./memory.module.css";

export function MemoryPage({enterpriseId}: { enterpriseId: string }) {
  return <EnterpriseGate enterpriseId={enterpriseId}>{({user, context}) => <Memories key={enterpriseId} user={user}
                                                                                     context={context}/>}</EnterpriseGate>;
}

function Memories({user, context}: { user: IdentityUser; context: EnterpriseContext }) {
  const uiText = useT();
  const enterpriseId = context.enterprise.id;
  const [query, setQuery] = useState("");
  const list = useApiPage<MemoryAgent>(organizationPath(enterpriseId, `/memories/agents?query=${encodeURIComponent(query)}`));
  return <PlatformShell user={user} context={context} area="user" title={uiText("已保存的偏好")}>
    <PageHeader title={uiText("已保存的偏好")} description={uiText("查看和管理为员工保存的工作偏好。")}
                actions={<><Link className={ui.button} href="/settings?tab=memory">{uiText("个人设置")}</Link></>}/>
    {!user.preferences.memoryEnabled && <p className={ui.notice}>{uiText("记忆已关闭。你仍可管理已经保存的偏好。")}</p>}
    <div className={styles.toolbar}><SearchInput aria-label={uiText("搜索员工名称")}
                                                 placeholder={uiText("搜索员工名称")} maxLength={100} value={query}
                                                 onChange={(event) => setQuery(event.target.value)}/><Button
      className={ui.button} disabled={list.loading} onClick={list.retry}>{uiText("刷新")}</Button></div>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)}
                empty={query ? uiText("没有匹配的员工。") : uiText("尚未保存偏好。")}>
      <div className={styles.list}>{list.data?.items.map((item) => <Link className={styles.agentRow} key={item.agentId}
                                                                         href={enterprisePath(enterpriseId, `/memories/${encodeURIComponent(item.agentId)}`)}><ResourceAvatar
        icon={item.agentIcon ?? ""} color={item.agentColor ?? undefined} size="small"/>
        <div><h2>{item.agentName}</h2><p>{item.memoryCount}{uiText(" 条偏好")}</p></div>
        <IconChevronRight size={19}/></Link>)}</div>
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
  </PlatformShell>;
}
