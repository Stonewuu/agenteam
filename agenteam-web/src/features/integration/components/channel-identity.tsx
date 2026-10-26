"use client";

import {ChannelProviderIcon} from "./channel-provider-icon";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./integration.module.css";

/** 连接与账号使用渠道品牌标志，名称和用途保持独立展示。 */
export function ChannelIdentity({name, providerName, providerCode, detail, compact = false}: {
  name: string; providerName: string; providerCode?: string; detail?: string | null; compact?: boolean;
}) {
  const t = useT();
  const provider = providerCode ?? (providerName === "飞书" ? "feishu" : ["企业微信", "企微智能机器人"].includes(providerName) ? "wecom" : undefined);
  return <div className={styles.channelIdentity} data-compact={compact || undefined}>
    <span className={styles.channelIcon} data-icon-surface><ChannelProviderIcon providerCode={provider} size={compact ? 20 : 26}/></span>
    <div><strong>{name}</strong><span>{t(providerName)}{detail ? ` · ${detail}` : ""}</span></div>
  </div>;
}
