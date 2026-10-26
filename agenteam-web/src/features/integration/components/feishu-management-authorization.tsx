"use client";

import { Button } from "@/components/ui/button";
import { IconKey, IconExternalLink } from "@/components/ui/icons";
import { useT } from "@/lib/i18n/locale-provider";
import ui from "@/components/ui/surface.module.css";
import styles from "./feishu-quick-create.module.css";

export function FeishuManagementAuthorization({
  name,
  busy,
  fallbackUrl,
  onAuthorize,
}: {
  name: string;
  busy: boolean;
  fallbackUrl?: string;
  onAuthorize: () => void;
}) {
  const t = useT();
  return (
    <section className={styles.authorization}>
      <span className={styles.authorizationIcon} data-icon-surface>
        <IconKey size={28} />
      </span>
      <h3 className={styles.progressTitle}>{t("授权管理岗位机器人")}</h3>
      <p className={styles.description}>{t("通过“{0}”，允许群策配置并发布你有管理权限的飞书应用。", [name])}</p>
      <Button type="button" className={ui.primary} disabled={busy} onClick={onAuthorize}>
        <IconExternalLink size={17} />
        {t("前往飞书授权")}
      </Button>
      <p className={styles.confirmationHint}>{t("授权完成后，此窗口会自动更新。")}</p>
      {fallbackUrl && (
        <a className={styles.textLink} href={fallbackUrl} target="_blank" rel="noreferrer">
          {t("浏览器未能打开授权页，点击继续")}
        </a>
      )}
    </section>
  );
}
