"use client";

import {IconBuilding, IconPlug} from "@/components/ui/icons";
import {BrandIcon} from "@/components/ui/brand-icon";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./integration.module.css";

/** 飞书使用官方品牌图标，其他渠道继续显示名称与通用语义图标。 */
export function ChannelIdentity({name, providerName, providerCode, detail, compact = false}: {
  name: string; providerName: string; providerCode?: string; detail?: string | null; compact?: boolean;
}) {
  const t = useT();
  const Icon = providerCode === "wecom" || providerName === "企业微信" ? IconBuilding
    : IconPlug;
  return <div className={styles.channelIdentity} data-compact={compact || undefined}>
    <span className={styles.channelIcon} data-icon-surface>{providerCode === "feishu" || providerName === "飞书"
      ? <BrandIcon brand="feishu" size={compact ? 20 : 26}/>
      : <Icon size={compact ? 18 : 23} variant="Bulk"/>}</span>
    <div><strong>{name}</strong><span>{t(providerName)}{detail ? ` · ${detail}` : ""}</span></div>
  </div>;
}
