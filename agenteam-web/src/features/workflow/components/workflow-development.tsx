"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {PageHeader} from "@/components/ui/page-header";
import {IconSchema} from "@/components/ui/icons";
import styles from "./workflow-development.module.css";

/** 工作流暂缓开放时，统一控制页面、配置和选择入口。 */
export const workflowInDevelopment = true;

export function WorkflowDevelopmentNotice() {
  const uiText = useT();
  return <section className={styles.notice} aria-label={uiText("工作流开发中")}>
    <div className={styles.heading}><IconSchema size={20} aria-hidden="true"/><h2>{uiText("工作流")}</h2><span
      className="badge neutral">{uiText("开发中")}</span></div>
    <p>{uiText("工作流功能暂未开放。")}</p>
  </section>;
}

export function WorkflowDevelopmentPage() {
  const uiText = useT();
  return <><PageHeader title={uiText("工作流")}/><WorkflowDevelopmentNotice/></>;
}
