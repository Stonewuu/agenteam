"use client";

import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {DetailSection} from "@/components/ui/detail-section";
import {IconBuilding, IconCopy, IconKey, IconPlug} from "@/components/ui/icons";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/field";
import {toast} from "@/components/ui/toast";
import {useT} from "@/lib/i18n/locale-provider";
import type {Integration} from "../types/integration";
import {ChannelIdentity} from "./channel-identity";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration.module.css";

export function IntegrationDetailsDialog({value, onClose}: {value: Integration; onClose: () => void}) {
  const t = useT();
  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(t("已复制"));
    } catch (error) {
      console.error("复制接入地址失败", error);
      toast.error(t("复制失败，请选中地址后手动复制。"));
    }
  }
  return <Dialog title={t("接入配置")} icon={<IconPlug size={21}/>} onClose={onClose}>
    <div className={ui.form}>
      <ChannelIdentity name={value.name} providerName={value.providerName} providerCode={value.providerCode}/>
      <DetailSection title={t("应用信息")} icon={<IconBuilding size={19}/>}>
        <dl className={styles.configFacts}>
          <div><dt>{t("应用编号")}</dt><dd>{value.externalAppId}</dd></div>
          {value.externalTenantId && <div><dt>{t("企业编号")}</dt><dd>{value.externalTenantId}</dd></div>}
        </dl><p className={styles.secretNote}><IconKey size={16}/>{value.secretConfigured ? t("应用密钥已设置。") : t("尚未设置应用密钥。")}</p>
      </DetailSection>
      <DetailSection title={t("登录与授权地址")} icon={<IconPlug size={19}/>}>
        <div className={ui.form}>{[{label: t("授权回调地址"), text: value.callbackUrl}, {label: t("企业登录链接"), text: value.loginUrl}].map(item =>
          <Field key={item.label} label={item.label}><div className={styles.copyField}>
            <Input aria-label={item.label} readOnly value={item.text} onFocus={event => event.target.select()}/>
            <Button className="icon-button" aria-label={t("复制{0}", [item.label])} onClick={() => void copy(item.text)}><IconCopy size={17}/></Button>
          </div></Field>)}</div>
      </DetailSection>
    </div><DialogActions><DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
  </Dialog>;
}
