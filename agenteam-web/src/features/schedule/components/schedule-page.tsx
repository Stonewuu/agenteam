"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {toast} from "@/components/ui/toast";

import {Button} from "@/components/ui/button";
import {CollectionViewToggle} from "@/components/ui/collection-view";
import {MotionPanel} from "@/components/ui/motion-panel";
import {LoadingState} from "@/components/ui/loading-state";
import {useCollectionView} from "@/lib/use-collection-view";

import {PageHeader} from "@/components/ui/page-header";

import {useRef, useState} from "react";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import type {Employee} from "@/features/employee/types/employee";
import {useApiQuery} from "@/lib/http/use-api-query";
import {QueryState} from "@/components/ui/query-state";
import {Tabs} from "@/components/ui/tabs";
import {SearchInput} from "@/components/ui/search-input";
import {IconCalendar, IconPlayerPause, IconPlayerPlay, IconPlus, IconRefresh} from "@/components/ui/icons";
import type {Schedule} from "../types/schedule";
import {ScheduleEditor} from "./schedule-editor";
import {ScheduleCollection} from "./schedule-collection";
import {ScheduleRecordsDialog} from "./schedule-records-dialog";
import {useSchedules} from "../hooks/use-schedules";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule.module.css";

export function SchedulePage({enterpriseId, initialAgent}: { enterpriseId: string; initialAgent?: string }) {
  return <EnterpriseGate enterpriseId={enterpriseId} permission="schedule.view">{({user, context}) => <ScheduleList
    key={enterpriseId} user={user} context={context} initialAgent={initialAgent}/>}</EnterpriseGate>;
}

function ScheduleList({user, context, initialAgent}: {
  user: IdentityUser;
  context: EnterpriseContext;
  initialAgent?: string
}) {
  const uiText = useT();
  const [view, setView] = useCollectionView();
  const enterpriseId = context.enterprise.id;
  const canCreate = context.permissions.includes("schedule.manage");
  const [query, setQuery] = useState("");
  const [creating, setCreating] = useState(Boolean(initialAgent) && canCreate);
  const [useInitial, setUseInitial] = useState(Boolean(initialAgent));
  const createTrigger = useRef<HTMLButtonElement>(null);

  const [filter, setFilter] = useState("all");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Schedule | null>(null);
  const [records, setRecords] = useState<string | null>(null);
  const employee = useApiQuery<Employee>(initialAgent && creating && useInitial ? organizationPath(enterpriseId, `/employees/${encodeURIComponent(initialAgent)}`) : null);
  const list = useSchedules(enterpriseId, query);
  const plans = list.data?.filter((plan) => filter === "all" || plan.enabled === (filter === "enabled")) ?? [];
  const currentPage = Math.min(page, Math.max(0, Math.ceil(plans.length / 12) - 1));
  const close = () => {
    setCreating(false);
    setUseInitial(false);
    requestAnimationFrame(() => createTrigger.current?.focus({preventScroll: true}));
  };
  return <PlatformShell user={user} context={context} area="user" title={uiText("我的计划")}>
    <div className={styles.page}>
      <PageHeader title={uiText("我的计划")} description={uiText("按时执行任务或发送通知。")}
                  actions={<>{canCreate && <Button ref={createTrigger} className={ui.primary} onClick={() => {
                    setUseInitial(false);
                    setCreating(true);
                  }}><IconPlus size={17}/>{uiText("创建计划")}</Button>}</>}/>
      <div className={styles.toolbar}><Tabs variant="filter" value={filter} onChange={(value) => {
        setFilter(value);
        setPage(0);
      }} items={[{
        value: "all",
        label: uiText("全部计划"),
        icon: IconCalendar
      }, {value: "enabled", label: uiText("已启用"), icon: IconPlayerPlay}, {
        value: "paused",
        label: uiText("已暂停"),
        icon: IconPlayerPause
      }]}/>
        <div className={ui.actions}><SearchInput maxLength={100} placeholder={uiText("搜索计划…")}
                                                 aria-label={uiText("搜索计划名称")} value={query}
                                                 onChange={(event) => {
                                                   setQuery(event.target.value);
                                                   setPage(0);
                                                 }}/><CollectionViewToggle value={view} onChange={setView}/><Button
          className="icon-button" aria-label={uiText("刷新计划")} disabled={list.loading}
          onClick={list.retry}><IconRefresh size={18}/></Button></div>
      </div>
      <MotionPanel value={view}><QueryState {...list} hasData={Boolean(plans.length)}
                                            empty={query || filter !== "all" ? uiText("没有符合条件的计划。") : uiText("暂无计划。")}
                                            loadingContent={view === "grid" ?
                                              <div className={styles.loadingCards}><LoadingState layout="cards"/>
                                              </div> : undefined}>
        <ScheduleCollection view={view} plans={plans.slice(currentPage * 12, (currentPage + 1) * 12)}
                            enterpriseId={enterpriseId} permissions={context.permissions}
                            onEdit={setEditing} onRecords={setRecords} onChanged={(message) => {
          if (message) {
            toast.success(message);
          }
          list.retry();
        }} onDeleted={list.retry}/>
      </QueryState></MotionPanel>{plans.length > 12 &&
      <nav className={ui.pagination} aria-label={uiText("计划翻页")}><Button className={ui.button}
                                                                             disabled={currentPage === 0 || list.loading}
                                                                             onClick={() => setPage(currentPage - 1)}>{uiText("上一页")}</Button><span>{currentPage + 1} / {Math.ceil(plans.length / 12)}</span><Button
        className={ui.button} disabled={(currentPage + 1) * 12 >= plans.length || list.loading}
        onClick={() => setPage(currentPage + 1)}>{uiText("下一页")}</Button></nav>}
      {creating && useInitial && initialAgent && !employee.data &&
        <QueryState {...employee} hasData={false} empty={uiText("该员工暂不可选择。")}/>}
      {creating && useInitial && employee.data && !employee.data.canRun && <p className={ui.error}
                                                                              role="alert">{employee.data.unavailableReason ? uiText(employee.data.unavailableReason) : uiText("该员工暂不可选择。")}</p>}
      {creating && (!useInitial || (employee.data?.canRun && employee.data.hireId)) &&
        <ScheduleEditor enterpriseId={enterpriseId} timezone={context.enterprise.timezone} initial={null}
                        employee={useInitial ? employee.data! : undefined}
                        showMarket={context.permissions.includes("agent.market_view")} onClose={close} onReload={() => {
          employee.retry();
          close();
          list.retry();
        }} onSaved={() => {
          close();
          toast.success(uiText("计划已保存。"));
          list.retry();
        }}/>}
      {editing && <ScheduleEditor enterpriseId={enterpriseId} timezone={context.enterprise.timezone} initial={editing}
                                  showMarket={context.permissions.includes("agent.market_view")}
                                  onClose={() => setEditing(null)} onReload={() => {
        setEditing(null);
        list.retry();
      }} onSaved={() => {
        setEditing(null);
        toast.success(uiText("计划已保存。"));
        list.retry();
      }}/>}
      {records &&
        <ScheduleRecordsDialog enterpriseId={enterpriseId} scheduleId={records} permissions={context.permissions}
                               onClose={() => {
                                 setRecords(null);
                                 list.retry();
                               }}/>}
    </div>
  </PlatformShell>;
}
