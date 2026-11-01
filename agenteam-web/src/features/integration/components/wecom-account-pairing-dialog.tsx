"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogActions, DialogCancel } from "@/components/ui/dialog";
import { IconCheck, IconCopy, IconRefresh } from "@/components/ui/icons";
import { Input } from "@/components/ui/input";
import { useT } from "@/lib/i18n/locale-provider";
import { ChannelProviderIcon } from "./channel-provider-icon";
import ui from "@/components/ui/surface.module.css";
import styles from "./wecom-account-pairing.module.css";

export type WecomAccountPairing = { command: string; expiresAt: string; name: string; connectionId: string };

export function WecomAccountPairingDialog({ pairing, busy, onRenew, onClose }: {
  pairing: WecomAccountPairing;
  busy: boolean;
  onRenew: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const [now, setNow] = useState(() => Date.now());
  const [copied, setCopied] = useState("");
  const [copyError, setCopyError] = useState(false);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const remaining = Math.max(0, Math.ceil((Date.parse(pairing.expiresAt) - now) / 1000));
  const expired = remaining === 0;
  return (
    <Dialog title={t("绑定企业微信账号")} icon={<ChannelProviderIcon providerCode="wecom" size={23} />} onClose={onClose} busy={busy}>
      <div className={styles.content}>
        <p className={styles.name}>{pairing.name}</p>
        <div className={styles.heading}>
          <h3>{t("复制指令，发给机器人")}</h3>
          <p>{t("在企微私聊本企业任意一个已接入的机器人，发送下方指令。")}</p>
        </div>
        <div className={styles.command}>
          <Input readOnly value={pairing.command} aria-label={t("绑定指令")} onFocus={(event) => event.target.select()} />
          <Button className={`${ui.primary} ${styles.copy}`} disabled={busy || expired} onClick={() => {
            void navigator.clipboard.writeText(pairing.command).then(() => {
              setCopied(pairing.command);
              setCopyError(false);
            }).catch(() => setCopyError(true));
          }}>
            {copied === pairing.command ? <IconCheck size={17} /> : <IconCopy size={17} />}
            {t(copied === pairing.command ? "已复制" : "复制指令")}
          </Button>
        </div>
        <div className={styles.validity}>
          <span>{expired ? t("指令已过期，请重新获取。") : t("{0} 分钟内有效", [Math.ceil(remaining / 60)])}</span>
          <Button className={`${ui.button} ${styles.renew}`} disabled={busy} onClick={onRenew}><IconRefresh size={15} />{t("重新获取")}</Button>
        </div>
        {copyError && <p role="status" className={styles.error}>{t("复制失败，请选中绑定指令后手动复制。")}</p>}
        <p className={styles.hint}>{t("绑定一次，即可使用本企业的其他机器人。完成后此窗口会自动关闭。")}</p>
      </div>
      <DialogActions><DialogCancel className={ui.button} disabled={busy}>{t("稍后绑定")}</DialogCancel></DialogActions>
    </Dialog>
  );
}
