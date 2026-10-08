"use client";

import {useT} from "@/lib/i18n/locale-provider";

import type {AgentConfig, DraftWrite} from "../types/resource";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import styles from "./resource.module.css";

export function AgentIntroductionPreview({draft}: { draft: DraftWrite }) {
  const uiText = useT();
  const config = draft.config as AgentConfig;
  return <aside className={styles.publicPreview} aria-label={uiText("员工公开介绍预览")}><p
    className={styles.previewCaption}>{uiText("员工介绍预览")}</p>
    <div className={styles.previewCard}>
      <ResourceAvatar icon={config.icon} color={config.color} size="large"/>
      <h2>{draft.name || uiText("智能体名称")}</h2>{config.businessRole &&
      <p className={styles.previewRole}>{config.businessRole}</p>}
      <p>{draft.description || uiText("填写简介，让同事了解这位员工可以协助的工作。")}</p>
      {config.publicExamples.length > 0 &&
        <ul>{config.publicExamples.map((example, index) => <li key={index}>{example}</li>)}</ul>}
    </div>
  </aside>;
}
