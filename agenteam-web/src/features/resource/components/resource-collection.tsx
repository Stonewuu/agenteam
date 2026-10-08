"use client";

import {useT} from "@/lib/i18n/locale-provider";

import Link from "next/link";
import {useState} from "react";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useApiPage} from "@/lib/http/use-api-query";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {Select} from "@/components/ui/select";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {MotionPanel} from "@/components/ui/motion-panel";
import {IconArrowUpRight} from "@/components/ui/icons";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {resourcePage, resourceStatus} from "../lib/resource-display";
import {resourceListCopy} from "../lib/resource-list-copy";
import type {ResourceKind, ResourceSummary} from "../types/resource";
import {ResourceActions} from "./resource-actions";
import {AgentListingControl} from "./agent-listing-control";
import styles from "./resource.module.css";
import ui from "@/components/ui/surface.module.css";

export function ResourceCollection({enterpriseId, kind, filters, refresh, onChanged, filtered, view}: {
  enterpriseId: string;
  kind: ResourceKind;
  filters: string;
  refresh: number;
  onChanged: () => void;
  filtered: boolean;
  view: "grid" | "list";
}) {
  const uiText = useT();
  const [limit, setLimit] = useState(30);
  const list = useApiPage<ResourceSummary>(organizationPath(enterpriseId, `/resources?${filters}`), refresh, 300, limit);
  const copy = resourceListCopy(kind, uiText);
  return <><MotionPanel value={view}><QueryState {...list} loadingLayout={view === "grid" ? "cards" : "rows"}
                                                 hasData={Boolean(list.data?.items.length)}
                                                 empty={filtered ? copy.noMatches : copy.empty}>
    {view === "grid" ?
      <div className={styles.grid} aria-busy={list.loading}>{list.data?.items.map((resource) => <article
        className={styles.resourceCard} key={resource.id}>
        <div className={styles.cardTop}><ResourceAvatar icon={resource.icon} color={resource.color}/><ResourceStatus
          resource={resource}/></div>
        <Link className={styles.cardMain} href={resourcePage(enterpriseId, resource.kind, resource.id)}>
          <h2>{resource.source === "builtin" ? uiText(resource.name) : resource.name}</h2>
          <p>{resource.source === "builtin" ? uiText(resource.description) : resource.description}</p></Link>
        <div className={styles.cardTags}>{resource.tags.map((tag) => <span className="tag"
                                                                           key={tag.id}>{tag.name}</span>)}</div>
        {resource.kind === "agent" &&
          <AgentListingControl enterpriseId={enterpriseId} resource={resource} onChanged={onChanged}/>}
        <footer className={styles.cardFooter}><span>{resource.owner.displayName} · <EnterpriseDateTime
          value={resource.updatedAt} dateOnly/></span>
          <div className={ui.actions}><ResourceActions enterpriseId={enterpriseId} resource={resource}
                                                       onChanged={onChanged} compact quickCopy/><Link
            className="icon-button" href={resourcePage(enterpriseId, resource.kind, resource.id)}
            aria-label={`${resource.allowedActions.includes("edit") ? uiText("编辑") : uiText("查看")}${resource.source === "builtin" ? uiText(resource.name) : resource.name}`}><IconArrowUpRight
            size={18}/></Link></div>
        </footer>
      </article>)}</div> : <div className={styles.tableWrap} aria-busy={list.loading}>
        <table className={styles.table}>
          <thead>
          <tr>
            <th>{uiText("名称")}</th>
            <th>{uiText("状态")}</th>
            <th>{uiText("维护人")}</th>
            <th>{uiText("最近修改")}</th>
            <th>{uiText("操作")}</th>
          </tr>
          </thead>
          <tbody>
          {list.data?.items.map((resource) => <tr key={resource.id}>
            <td><Link className={styles.tableName}
                      href={resourcePage(enterpriseId, resource.kind, resource.id)}><ResourceAvatar icon={resource.icon}
                                                                                                    color={resource.color}
                                                                                                    size="small"/><span>{resource.source === "builtin" ? uiText(resource.name) : resource.name}</span></Link><span
              className={styles.secondary}>{resource.source === "builtin" ? uiText(resource.description) : resource.description}</span>
            </td>
            <td><ResourceStatus resource={resource}/>{resource.kind === "agent" &&
              <AgentListingControl enterpriseId={enterpriseId} resource={resource} onChanged={onChanged}/>}</td>
            <td>{resource.owner.displayName}</td>
            <td><EnterpriseDateTime value={resource.updatedAt}/></td>
            <td><ResourceActions enterpriseId={enterpriseId} resource={resource} onChanged={onChanged} compact/></td>
          </tr>)}
          </tbody>
        </table>
      </div>}
  </QueryState></MotionPanel>
    <footer className={styles.collectionFooter}>
      <span>{list.data && !list.loading ? uiText("本页 {0} 项", [list.data.items.length]) : ""}</span><Select
      aria-label={uiText("每页显示数量")} value={limit} onChange={(event) => setLimit(Number(event.target.value))}>
      <option value="30">{uiText("30 项 / 页")}</option>
      <option value="50">{uiText("50 项 / 页")}</option>
      <option value="100">{uiText("100 项 / 页")}</option>
    </Select><Pagination {...list} hasMore={list.data?.hasMore}/></footer>
  </>;
}

export function ResourceStatus({resource}: { resource: ResourceSummary }) {
  const uiText = useT();
  const status = resourceStatus(resource);
  return <span className={styles.resourceStatus}><span
    className={`badge ${status === "已发布" ? "mint" : status === "草稿" ? "neutral" : "amber"}`}>{uiText(status)}</span>{resource.hasUnpublishedChanges &&
    <small>{uiText("有未发布修改")}</small>}</span>;
}
