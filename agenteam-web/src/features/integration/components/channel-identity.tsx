"use client";

import {IconBuilding, IconMessages, IconPlug} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./integration.module.css";

/** 使用平台名称配合统一语义图标，不把通用图标冒充平台标识。 */
export function ChannelIdentity({name, providerName, providerCode, detail, compact = false}: {
  name: string; providerName: string; providerCode?: string; detail?: string | null; compact?: boolean;
}) {
  const t = useT();
  const Icon = providerCode === "wecom" || providerName === "企业微信" ? IconBuilding
    : providerCode === "feishu" || providerName === "飞书" ? IconMessages : IconPlug;
  return <div className={styles.channelIdentity} data-compact={compact || undefined}>
    <span className={styles.channelIcon}><Icon size={compact ? 18 : 23} variant="Bulk"/></span>
    <div><strong>{name}</strong><span>{t(providerName)}{detail ? ` · ${detail}` : ""}</span></div>
  </div>;
}
