"use client";

import {Button} from "@/components/ui/button";
import {IconRefresh} from "@/components/ui/icons";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./management-version.module.css";

export function ManagementVersion() {
  const t = useT();
  const query = useApiQuery<{version: string | null}>("/api/v1/application", 0, 0, false, {revalidateOnFocus: true});
  const version = query.data?.version;
  return <div className={styles.version} aria-busy={query.loading}>
    <span>{t("当前版本")}</span>
    <span className={styles.value}>{query.error ? t("读取失败") : query.loading && !query.data ? t("正在读取…")
      : version ? (version.startsWith("v") ? version : `v${version}`) : "—"}</span>
    {query.error && <Button type="button" className={styles.retry} onClick={query.retry}
                           aria-label={t("重新读取版本")}><IconRefresh size={15}/></Button>}
  </div>;
}
