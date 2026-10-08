"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useEffect, useId, useRef, useState} from "react";
import {usePathname, useRouter} from "next/navigation";
import Link from "next/link";
import {SearchInput} from "@/components/ui/search-input";
import {Select} from "@/components/ui/select";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "@/components/ui/shadcn/collapsible";
import {IconAdjustments, IconMessages, IconRefresh, IconStar} from "@/components/ui/icons";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {EmployeeIdentity} from "@/features/employee/components/employee-identity";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {ConversationActions} from "./conversation-actions";
import {ConversationManagementDialog} from "./conversation-management-dialog";
import {useConversationNavigation} from "./conversation-navigation-context";
import type {Conversation} from "../types/execution";
import {groupConversationsByDate} from "../lib/conversation-date-groups";
import styles from "./conversation-frame.module.css";

export function ConversationRail({onNavigate}: { onNavigate: () => void }) {
  const uiText = useT();
  const navigation = useConversationNavigation();
  const pathname = usePathname();
  const router = useRouter();
  const [managed, setManaged] = useState<{ value: Conversation; operation: "rename" | "delete" } | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const filterTrigger = useRef<HTMLButtonElement>(null);
  const groupId = useId();
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const updateDate = () => setNow(new Date());
    const timer = window.setInterval(updateDate, 60_000);
    window.addEventListener("focus", updateDate);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", updateDate);
    };
  }, []);

  if (!navigation) {
    return null;
  }
  const {list, context} = navigation;
  const enterprise = context.enterprise.id;
  const root = `/enterprises/${encodeURIComponent(enterprise)}`;
  const selectedId = pathname.split("/")[3] === "conversations" ? pathname.split("/")[4] : null;
  const canManage = context.permissions.includes("conversation.manage");
  const category = list.filter.status === "deleted" ? "deleted" : list.filter.status === "archived" ? "archived" : list.filter.favorite ? "favorite" : "all";
  const activeFilters = Number(category !== "all") + Number(Boolean(list.filter.agentId));
  const employees = list.employeeOptions;
  const groups = groupConversationsByDate(list.items, context.enterprise.timezone, now);

  function changed(conversationId: string) {
    list.refresh();
    window.dispatchEvent(new CustomEvent("agenteam:conversation-changed", {
      detail: {
        enterpriseId: enterprise,
        conversationId
      }
    }));
  }

  return <div className={styles.rail}>
    <header className={styles.railHeader}><h2>{uiText("对话任务")}</h2></header>
    <div className={styles.railBody}>
      <Collapsible className={styles.filterDisclosure} open={filtersOpen} onOpenChange={setFiltersOpen}
                   onKeyDown={(event) => {
                     if (filtersOpen && event.key === "Escape" && !event.defaultPrevented && event.currentTarget.contains(event.target as Node)) {
                       event.preventDefault();
                       event.stopPropagation();
                       filterTrigger.current?.focus({preventScroll: true});
                       setFiltersOpen(false);
                     }
                   }}>
        <div className={styles.filterTools}>
          <SearchInput aria-label={uiText("搜索对话")} placeholder={uiText("搜索对话…")} value={list.filter.query}
                       onChange={(event) => list.setFilter((current) => ({...current, query: event.target.value}))}
                       onClear={() => list.setFilter((current) => ({...current, query: ""}))}/>
          <CollapsibleTrigger ref={filterTrigger} className={`icon-button ${styles.filterToggle}`} type="button"
                              data-active={activeFilters > 0}
                              aria-label={activeFilters ? uiText("高级筛选，{0} 项条件", [activeFilters]) : uiText("高级筛选")}
                              title={filtersOpen ? uiText("收起高级筛选") : uiText("展开高级筛选")}>
            <IconAdjustments className={styles.filterSettingsIcon} size={18}/>
          </CollapsibleTrigger>
          <Button type="button" className="icon-button" aria-label={uiText("刷新对话列表")}
                  title={uiText("刷新对话列表")} disabled={list.loading} onClick={list.refresh}><IconRefresh size={18}/></Button>
        </div>
        <CollapsibleContent className="agenteam-disclosure-content" keepMounted inert={!filtersOpen}>
          <div className={styles.railFilters}>
            <Select aria-label={uiText("对话分类")} value={category} onChange={(event) => list.setFilter((current) => ({
              ...current,
              status: event.target.value === "archived" ? "archived" : event.target.value === "deleted" ? "deleted" : "active",
              favorite: event.target.value === "favorite"
            }))}>
              <option value="all">{uiText("全部对话")}</option>
              <option value="favorite">{uiText("已收藏")}</option>
              <option value="archived">{uiText("已归档")}</option>
              {canManage && <option value="deleted">{uiText("已删除")}</option>}</Select>
            <Select aria-label={uiText("筛选对话数字员工")} value={list.filter.agentId}
                    onChange={(event) => list.setFilter((current) => ({...current, agentId: event.target.value}))}
                    renderOption={(option) => {
                      const employee = employees.get(option.value);
                      return employee ? <EmployeeIdentity {...employee} /> : option.label;
                    }}>
              <option value="">{uiText("全部数字员工")}</option>
              {list.filter.agentId && !employees.has(list.filter.agentId) && <option
                value={list.filter.agentId}>{uiText("所选数字员工")}</option>}{[...employees].map(([id, employee]) =>
              <option key={id} value={id}>{employee.name}</option>)}</Select>
          </div>
        </CollapsibleContent>
      </Collapsible>
      <div className={styles.railList} aria-busy={list.loading}>
        {list.error &&
          <div className={styles.railError} role="alert"><p>{localizeUiMessage(list.error ?? "", uiText)}</p><Button
            type="button" onClick={list.refresh}>{uiText("重新加载")}</Button></div>}
        {!list.items.length && <div className={styles.railEmpty}><IconMessages size={25}/>
          <p>{list.loading ? uiText("正在读取对话…") : list.filter.query || list.filter.agentId ? uiText("没有匹配的对话。") : uiText("暂无此类对话。")}</p>
        </div>}
        {groups.map((group) => (
          <section key={group.key} className={styles.dateGroup} aria-labelledby={`${groupId}-${group.key}`}>
            <h3 id={`${groupId}-${group.key}`} className={styles.dateGroupTitle}>{uiText(group.label)}</h3>
            {group.items.map((conversation) => (
              <article key={conversation.id} className={styles.conversationItem}
                       data-active={conversation.id === selectedId} data-manageable={canManage}>
                {conversation.status === "deleted" ? (
                  <div className={styles.conversationLink}>
                    <strong title={conversation.title}>{conversation.title}</strong>
                    <small>{uiText("已删除")}</small>
                  </div>
                ) : (
                  <Link className={styles.conversationLink}
                        href={`${root}/conversations/${encodeURIComponent(conversation.id)}`}
                        aria-current={conversation.id === selectedId ? "page" : undefined} onClick={onNavigate}>
                    <strong title={conversation.title}>{conversation.title}</strong>
                    <span className={styles.conversationMeta}>
                      {conversation.agentName && <span className={styles.agentName}>
                        <ResourceAvatar icon={conversation.agentIcon ?? ""} color={conversation.agentColor ?? undefined}
                                        size="tiny"/>
                        <small title={conversation.agentName}>{conversation.agentName}</small>
                      </span>}
                      {conversation.favorite &&
                        <span className={styles.favoriteIcon} role="img" aria-label={uiText("已收藏")}><IconStar
                          size={12} variant="Bold"/></span>}
                      <EnterpriseDateTime value={conversation.updatedAt} dateOnly={group.key !== "today"}
                                          timeOnly={group.key === "today"}/>
                    </span>
                  </Link>
                )}
                {canManage && <div className={styles.itemActions}>
                  <ConversationActions enterprise={enterprise} conversation={conversation}
                                       onManage={(operation) => setManaged({value: conversation, operation})}
                                       onChanged={() => changed(conversation.id)}/>
                </div>}
              </article>
            ))}
          </section>
        ))}
        {list.hasMore && <Button className={styles.loadMore} disabled={list.loading}
                                 onClick={() => void list.more()}>{list.loading ? uiText("正在读取…") : uiText("加载更多对话")}</Button>}
      </div>
    </div>
    {managed && <ConversationManagementDialog enterprise={enterprise} conversation={managed.value}
                                              initialAction={managed.operation} onClose={() => setManaged(null)}
                                              onChanged={(action, value) => {
                                                changed(managed.value.id);
                                                if (action === "deleted" && selectedId === managed.value.id) {
                                                  router.replace(`${root}/new-task`);
                                                }
                                                if (action === "restored" && value) {
                                                  onNavigate();
                                                  router.push(`${root}/conversations/${encodeURIComponent(value.id)}`);
                                                }
                                              }}/>}
  </div>;
}
