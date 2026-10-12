"use client";

import {useT} from "@/lib/i18n/locale-provider";
import type {UsableVersion} from "@/features/resource/types/resource";
import styles from "./plugin-type-labels.module.css";

export function PluginTypeLabels({types}: {types: UsableVersion["pluginTypes"]}) {
  const t = useT();
  if (!types?.length) {
    return null;
  }
  const labels = {builtin: t("内置插件"), mcp: t("远程插件"), java: t("扩展插件")};
  return <span className={styles.labels}>{types.map((type) => <span className={styles.label}
    key={type}>{labels[type]}</span>)}</span>;
}
