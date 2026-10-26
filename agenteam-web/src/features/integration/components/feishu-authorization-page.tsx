"use client";

import { useEffect } from "react";
import { BrandMark } from "@/components/ui/brand-mark";
import { LanguageMenu } from "@/features/auth/components/language-menu";
import { ThemeMenu } from "@/features/auth/components/theme-menu";
import { QueryState } from "@/components/ui/query-state";
import { IconAlertCircle, IconCheck, IconRefresh, IconX } from "@/components/ui/icons";
import { Button } from "@/components/ui/button";
import { returnFeishuAuthorization } from "../lib/feishu-authorization-channel";
import { useApiQuery } from "@/lib/http/use-api-query";
import { useT } from "@/lib/i18n/locale-provider";
import ui from "@/components/ui/surface.module.css";
import styles from "./feishu-authorization-page.module.css";

export function FeishuAuthorizationPage() {
  const t = useT();
  const result = useApiQuery<{ success: boolean; message: string; connectionId?: string }>(
    "/api/v1/auth/feishu-management-result",
  );
  useEffect(() => {
    if (!result.data?.success || !result.data.connectionId || typeof BroadcastChannel === "undefined") {
      return;
    }
    return returnFeishuAuthorization(result.data.connectionId, () => {
      window.close();
    });
  }, [result.data?.success, result.data?.connectionId]);
  return (
    <div className={styles.page}>
      <div className={styles.ambient} aria-hidden="true" />
      <header className={styles.topbar}>
        <div className={styles.brand}>
          <span className={styles.brandMark}>
            <BrandMark size={27} />
          </span>
          <strong>AgenTeam</strong>
        </div>
        <div className={styles.preferences}>
          <LanguageMenu />
          <ThemeMenu />
        </div>
      </header>
      <main className={styles.main}>
        <section className={styles.panel} aria-label={t("飞书管理授权")}>
          <p className={styles.context}>{t("飞书管理授权")}</p>
          <QueryState
            {...result}
            hasData={Boolean(result.data)}
            empty={t("请从机器人创建页面发起授权。")}
            loadingContent={
              <div className={styles.result} role="status">
                <span className={styles.icon} data-icon-context="inherit">
                  <IconRefresh size={34} className={styles.spinning} />
                </span>
                <h1>{t("正在确认授权")}</h1>
                <p className={styles.description}>{t("请稍候，正在读取飞书授权结果。")}</p>
              </div>
            }
          >
            {result.data && <FeishuAuthorizationResult success={result.data.success} message={result.data.message} />}
          </QueryState>
        </section>
      </main>
    </div>
  );
}

export function FeishuAuthorizationResult({ success, message }: { success: boolean; message: string }) {
  const t = useT();
  return (
    <div className={styles.result} data-success={success}>
      <span className={styles.icon} data-icon-context="inherit" aria-hidden="true">
        {success ? <IconCheck size={36} /> : <IconAlertCircle size={36} />}
      </span>
      <h1>{t(success ? "授权已完成" : "授权未完成")}</h1>
      <p className={styles.description} role={success ? "status" : "alert"}>
        {t(success ? "可以关闭此页，回到原来的群策窗口继续。" : message)}
      </p>
      <Button className={ui.primary} onClick={() => window.close()}>
        <IconX size={16} />
        {t("关闭授权页")}
      </Button>
    </div>
  );
}
