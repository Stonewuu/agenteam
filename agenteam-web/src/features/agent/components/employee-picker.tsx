"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";

import type {RefObject} from "react";
import {useSelectionQuery} from "@/components/ui/use-selection-query";
import Link from "next/link";
import {SelectionAction, type SelectionAnchor, SelectionSurface} from "@/components/ui/selection-surface";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconSearch} from "@/components/ui/icons";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {Employee} from "@/features/employee/types/employee";
import ui from "@/components/ui/surface.module.css";
import styles from "./conversation-controls.module.css";

export function EmployeePicker({
                                 enterprise,
                                 selectedId,
                                 onSelect,
                                 onClose,
                                 title: customTitle,
                                 showMarket = true,
                                 anchor,
                                 returnFocus,
                                 initialQuery = ""
                               }: {
  enterprise: string; onSelect: (employee: Employee) => void; onClose: () => void; title?: string; showMarket?: boolean;
  selectedId?: string | null;
  anchor?: SelectionAnchor | null; returnFocus?: RefObject<HTMLElement | null>; initialQuery?: string;
}) {
  const uiText = useT();
  const title = customTitle ?? uiText("选择员工");
  const [query, setQuery] = useSelectionQuery(initialQuery);
  const employees = useApiPage<Employee>(organizationPath(enterprise, `/employees?tab=mine&query=${encodeURIComponent(query)}`));
  return <SelectionSurface title={title} onClose={onClose} anchor={anchor} returnFocus={returnFocus}>
    <div className="selection-search"><span className="search-input"><IconSearch size={17}/><Input type="search"
                                                                                                   aria-label={uiText("查找已雇佣员工")}
                                                                                                   placeholder={uiText("查找已雇佣员工")}
                                                                                                   value={query}
                                                                                                   onChange={(event) => setQuery(event.target.value)}/></span>
    </div>
    <div className="selection-results">
      <QueryState {...employees} loadingContent={<EmployeePickerLoading/>}
                  hasData={Boolean(employees.data?.items.length)}
                  empty={showMarket ? uiText("没有可选择的员工，可以到员工广场雇佣。") : uiText("没有可选择的已雇佣员工。")}>
        <div className={styles.employeeOptions} data-selection-list>{employees.data?.items.map((employee) =>
          <SelectionAction data-selection-option type="button" className={styles.employeeOption} key={employee.agentId}
                           aria-pressed={employee.agentId === selectedId}
                           disabled={!employee.canRun || employees.loading}
                           onAction={(close) => close(() => onSelect(employee))}>
            <ResourceAvatar icon={employee.icon} color={employee.color} size="small"/><span
            className={styles.employeeIdentity}><strong>{employee.name}</strong><span>{employee.unavailableReason ? uiText(employee.unavailableReason) : employee.businessRole}</span></span>
            {employee.agentId === selectedId &&
              <svg className={styles.employeeSelected} width="16" height="16" viewBox="0 0 24 24" fill="none"
                   stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
                   aria-hidden="true">
                <path d="m5 12 4 4L19 6"/>
              </svg>}
          </SelectionAction>)}</div>
      </QueryState></div>
    <Pagination {...employees} hasMore={employees.data?.hasMore}/>
    {showMarket && <div className="selection-footer"><Link className={ui.button}
                                                           href={`/enterprises/${encodeURIComponent(enterprise)}/employees`}>{uiText("查看员工广场")}</Link>
    </div>}
  </SelectionSurface>;
}

function EmployeePickerLoading() {
  const uiText = useT();
  return <div className={styles.employeeOptions} data-selection-list data-loading-placeholder role="status"
              aria-label={uiText("正在加载员工…")} aria-busy="true">
    {[0, 1].map((index) => <div className={`${styles.employeeOption} ${styles.employeePlaceholder}`} key={index}
                                aria-hidden="true">
      <span className={styles.employeePlaceholderAvatar}/><span className={styles.employeeIdentity}><span
      className={styles.employeePlaceholderName}/><span className={styles.employeePlaceholderDescription}/></span>
    </div>)}
  </div>;
}
