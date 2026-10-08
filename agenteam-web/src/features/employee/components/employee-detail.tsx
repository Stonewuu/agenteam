"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Dialog} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconAlertCircle, IconBook2, IconMessages, IconSparkles, IconUsers} from "@/components/ui/icons";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {EmployeeMemoryLink} from "@/features/memory/components/employee-memory-link";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {Employee} from "../types/employee";
import {EmployeeActions} from "./employee-actions";
import styles from "./employee-detail.module.css";

const statusNames: Record<Employee["hireStatus"], string> = {
  none: "", pending: "申请待审批", active: "已雇佣", paused: "已暂停", terminated: "已解除雇佣",
};

export function EmployeeDetail({enterpriseId, id, permissions, refresh, onChanged, onClose, initial}: {
  enterpriseId: string; id: string; permissions: string[]; refresh: number; onChanged: () => void; onClose: () => void;
  initial?: Employee;
}) {
  const uiText = useT();
  const detail = useApiQuery<Employee>(organizationPath(enterpriseId, `/employees/${encodeURIComponent(id)}`), refresh);
  const employee = detail.data;
  const identity = employee ?? (detail.loading && !detail.error && initial?.agentId === id ? initial : undefined);
  return <Dialog title={uiText("员工介绍")} onClose={onClose} drawer className={styles.drawer}
                 bodyClassName={styles.body}
                 headerDetails={<span className={styles.headerNote}><IconUsers size={14}/>{uiText("数字员工")}</span>}
                 footer={employee && !detail.error ?
                   <EmployeeActions enterpriseId={enterpriseId} employee={employee} permissions={permissions}
                                    onChanged={onChanged} disabled={detail.loading}/> : undefined}>
    {identity && <div className={styles.identity}>
      <ResourceAvatar icon={identity.icon} color={identity.color} size="large"/>
      <div className={styles.identityCopy}><h3>{identity.name}</h3>{identity.businessRole &&
        <p>{identity.businessRole}</p>}</div>
      {localizeCatalog(statusNames, uiText)[identity.hireStatus] &&
        <span className={styles.status}>{localizeCatalog(statusNames, uiText)[identity.hireStatus]}</span>}
    </div>}
    <QueryState {...detail} hasData={Boolean(employee)} empty={uiText("无法访问这位员工。")} contentLayout="flow">
      {employee && <div className={styles.content}>
        {employee.description && <p className={styles.description}>{employee.description}</p>}
        {employee.tags.length > 0 &&
          <div className={styles.tags} aria-label={uiText("员工专长")}>{employee.tags.map((tag) => <span
            key={tag}>{tag}</span>)}</div>}
        {employee.examples.length > 0 &&
          <section className={styles.section}><h3><IconMessages size={18} variant="Bulk"/>{uiText("可以一起做的事")}
          </h3>
            <ul className={styles.examples}>{employee.examples.map((example, index) => <li key={`${index}:${example}`}>
              <span className={styles.exampleIcon}><IconSparkles size={15}/></span><p>{example}</p></li>)}</ul>
          </section>}
        {employee.skills.length > 0 && <section className={styles.section}><h3><IconBook2 size={18}
                                                                                          variant="Bulk"/>{uiText("擅长的技能")}<span>{employee.skills.length}</span>
        </h3>
          <ul className={styles.skills}>{employee.skills.map((skill) => <li key={skill.id}><span
            className={styles.skillIcon}><IconBook2 size={18} variant="Bulk"/></span>
            <div><strong>{skill.name}</strong>{skill.description && <p>{skill.description}</p>}</div>
          </li>)}</ul>
        </section>}
        {employee.unavailableReason &&
          <p className={styles.reason}><IconAlertCircle size={18}/><span>{uiText(employee.unavailableReason)}</span>
          </p>}
        {employee.canRun && permissions.includes("agent.run") &&
          <EmployeeMemoryLink enterpriseId={enterpriseId} agentId={employee.agentId}/>}
      </div>}
    </QueryState>
  </Dialog>;
}
