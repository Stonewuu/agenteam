"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";
import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";

import {PageHeader} from "@/components/ui/page-header";

import Link from "next/link";
import {Tabs} from "@/components/ui/tabs";
import {MotionPanel} from "@/components/ui/motion-panel";
import {SearchInput} from "@/components/ui/search-input";
import {
  IconAdjustments,
  IconArrowRight,
  IconFileText,
  IconMessages,
  IconRefresh,
  IconUser,
  IconUsers
} from "@/components/ui/icons";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {useState} from "react";
import {usePathname, useSearchParams} from "next/navigation";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useApiPage} from "@/lib/http/use-api-query";
import {Pagination, QueryState} from "@/components/ui/query-state";

import type {Employee} from "../types/employee";
import {EmployeeDetail} from "./employee-detail";
import {HireApplicationDetail, HireApplications} from "./hire-applications";
import ui from "@/components/ui/surface.module.css";
import styles from "./employee.module.css";

const hireLabels: Record<Employee["hireStatus"], string> = {
  none: "",
  pending: "待审批",
  active: "已雇佣",
  paused: "已暂停",
  terminated: "已解除雇佣"
};

export function EmployeePage({enterpriseId}: { enterpriseId: string }) {
  return <EnterpriseGate key={enterpriseId} enterpriseId={enterpriseId}
                         permission={["agent.market_view", "agent.run", "agent.hire", "agent.hire_approve"]}>
    {({user, context}) => <EmployeeContent key={enterpriseId} user={user} context={context}/>}
  </EnterpriseGate>;
}

function EmployeeContent({user, context}: { user: IdentityUser; context: EnterpriseContext }) {
  const uiText = useT();
  const params = useSearchParams();
  const pathname = usePathname();
  const canApplications = context.permissions.some((value) => ["agent.hire", "agent.hire_approve"].includes(value));
  const canMarket = context.permissions.includes("agent.market_view");
  const canMine = canMarket || context.permissions.includes("agent.run");
  const tab = params.get("tab") === "applications" && canApplications ? "applications" : params.get("tab") === "mine" && canMine ? "mine" : canMarket ? "market" : canMine ? "mine" : "applications";
  const query = params.get("query") ?? "";
  const [refresh, setRefresh] = useState(0);
  const [selectedEmployee, setSelectedEmployee] = useState<Employee>();
  const changed = () => setRefresh((value) => value + 1);
  const update = (key: string, value: string, replace = false) => {
    const next = new URLSearchParams(params.toString());
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    if (key === "tab") {
      next.delete("employee");
      next.delete("application");
      next.delete("notification");
      next.delete("query");
    }
    if (key === "application" && !value) {
      next.delete("notification");
    }
    const path = `${pathname}${next.size ? `?${next}` : ""}`;
    if (replace) {
      window.history.replaceState(null, "", path);
    } else {
      window.history.pushState(null, "", path);
    }
  };
  return <PlatformShell user={user} context={context} area="user" title={uiText("数字员工")}>
    <div className={styles.page}>
      <PageHeader title={uiText("找到合拍的数字员工")} description={uiText("各有所长，随时一起把工作向前推进。")}
                  inlineActions actions={<Button className="icon-button" aria-label={uiText("刷新员工")}
                                                 onClick={changed}><IconRefresh size={18}/></Button>}/>
      <div className={styles.tabs}><Tabs value={tab} onChange={(value) => update("tab", value)}
                                         label={uiText("员工分类")} items={[...(canMarket ? [{
        value: "market",
        label: uiText("员工广场"),
        icon: IconUsers
      }] : []), ...(canMine ? [{
        value: "mine",
        label: uiText("我的员工"),
        icon: IconUser
      }] : []), ...(canApplications ? [{
        value: "applications",
        label: uiText("雇佣申请"),
        icon: IconFileText
      }] : [])]}/>{tab !== "applications" &&
        <SearchInput value={query} onChange={(event) => update("query", event.target.value, true)}
                     placeholder={uiText("搜索员工或专长…")} aria-label={uiText("搜索员工名称")}/>}</div>
      <MotionPanel value={tab}>
        {tab === "applications" ?
          <HireApplications key="applications" enterpriseId={context.enterprise.id} refresh={refresh}
                            onChanged={changed}/>
          : <EmployeeList key={tab} enterpriseId={context.enterprise.id} permissions={context.permissions} tab={tab}
                          query={query} onQuery={(value) => update("query", value, true)}
                          refresh={refresh} onSelect={(employee) => {
            setSelectedEmployee(employee);
            update("employee", employee.agentId);
          }}/>}
      </MotionPanel>
      {params.get("employee") &&
        <EmployeeDetail key={params.get("employee")} enterpriseId={context.enterprise.id} id={params.get("employee")!}
                        permissions={context.permissions} refresh={refresh} initial={selectedEmployee}
                        onChanged={changed} onClose={() => update("employee", "", true)}/>}
      {tab === "applications" && params.get("application") &&
        <HireApplicationDetail key={params.get("application")} enterpriseId={context.enterprise.id}
                               id={params.get("application")!}
                               refresh={refresh} onClose={() => update("application", "", true)}/>}
    </div>
  </PlatformShell>;
}

function EmployeeList({enterpriseId, permissions, tab, query, onQuery, refresh, onSelect}: {
  enterpriseId: string;
  permissions: string[];
  tab: "market" | "mine";
  query: string;
  onQuery: (value: string) => void;
  refresh: number;
  onSelect: (employee: Employee) => void;
}) {
  const uiText = useT();
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [tagQuery, setTagQuery] = useState("");
  const [showTags, setShowTags] = useState(false);
  const tags = useApiPage<{
    id: string;
    name: string
  }>(organizationPath(enterpriseId, `/tags?query=${encodeURIComponent(tagQuery)}`));
  const search = new URLSearchParams({tab, query});
  if (selectedTags.length) {
    search.set("tagIds", selectedTags.join(","));
  }
  const list = useApiPage<Employee>(organizationPath(enterpriseId, `/employees?${search}`), refresh);
  return <>
    <div className={styles.toolbar}>
      <Button className={ui.button} aria-expanded={showTags}
              onClick={() => setShowTags((value) => !value)}><IconAdjustments
        size={16}/>{uiText("专长标签")}{selectedTags.length ? ` · ${selectedTags.length}` : ""}</Button>
      {(query || selectedTags.length > 0) && <Button className={ui.button} onClick={() => {
        onQuery("");
        setSelectedTags([]);
      }}>{uiText("清除筛选")}</Button>}</div>
    {showTags && <Fieldset className={styles.tagPicker}>
      <legend>{uiText("专长标签")}</legend>
      <Input className={ui.input} value={tagQuery} aria-label={uiText("查找标签")} placeholder={uiText("查找标签")}
             onChange={(event) => setTagQuery(event.target.value)}/>
      <QueryState {...tags} hasData={Boolean(tags.data?.items.length)} empty={uiText("没有匹配的标签。")}>
        <div className={styles.tagOptions}>{tags.data?.items.map((tag) => <label className={ui.check}
                                                                                 key={tag.id}><Checkbox
          checked={selectedTags.includes(tag.id)} disabled={!selectedTags.includes(tag.id) && selectedTags.length >= 10}
          onCheckedChange={(checked) => setSelectedTags((values) => checked ? [...values, tag.id] : values.filter((id) => id !== tag.id))}/>{tag.name}
        </label>)}</div>
      </QueryState><Pagination {...tags} hasMore={tags.data?.hasMore}/>
    </Fieldset>}
    <QueryState {...list} hasData={Boolean(list.data?.items.length)}
                empty={query || selectedTags.length ? uiText("没有匹配的员工，请调整筛选条件。") : tab === "mine" ? uiText("还没有雇佣员工，可以先到员工广场看看。") : uiText("暂无可查看的员工。")}>
      <div className={styles.grid} aria-busy={list.loading}>{list.data?.items.map((employee) => <article
        key={employee.agentId} className={styles.card}>
        <div className={styles.cardTop}><ResourceAvatar icon={employee.icon} color={employee.color} size="large"/>
          {localizeCatalog(hireLabels, uiText)[employee.hireStatus] && <span
            className={`badge ${employee.hireStatus === "active" ? "mint" : "neutral"}`}>{localizeCatalog(hireLabels, uiText)[employee.hireStatus]}</span>}
        </div>
        <Button className={styles.employeeName} onClick={() => onSelect(employee)}>{employee.name}</Button>
        <p className={styles.role}>{employee.businessRole}</p><p
        className={styles.introduction}>{employee.description}</p>
        {employee.tags.length > 0 && <div className={ui.chips}>{employee.tags.map((tag) => <span key={tag}
                                                                                                 className={ui.chip}>{tag}</span>)}</div>}
        {employee.unavailableReason && <p className={styles.reason}>{uiText(employee.unavailableReason)}</p>}
        <footer className={styles.cardFooter}><Button className="text-button"
                                                      onClick={() => onSelect(employee)}>{uiText("查看详情")}<IconArrowRight
          size={15}/></Button>
          {employee.canRun && permissions.includes("conversation.view") ?
            <Link className={`button small ${styles.startChat}`}
                  href={`/enterprises/${encodeURIComponent(enterpriseId)}/new-task?agent=${encodeURIComponent(employee.agentId)}`}><IconMessages
              size={16}/>{uiText("开始对话")}</Link>
            : (employee.canHire || employee.canResume) && <Button className="button primary small"
                                                                  onClick={() => onSelect(employee)}>{employee.canResume ? uiText("恢复雇佣") : employee.requiresApproval ? uiText("申请雇佣") : uiText("雇佣员工")}</Button>}
        </footer>
      </article>)}</div>
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
  </>;
}
