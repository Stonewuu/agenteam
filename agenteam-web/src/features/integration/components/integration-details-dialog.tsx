"use client";

import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {DetailSection} from "@/components/ui/detail-section";
import {IconBuilding, IconKey, IconPlug} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import type {Integration} from "../types/integration";
import {ChannelIdentity} from "./channel-identity";
import {IntegrationSetupGuide} from "./integration-setup-guide";
import {IntegrationAddressField} from "./integration-address-field";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration.module.css";

export function IntegrationDetailsDialog({value, onClose}: {value: Integration; onClose: () => void}) {
  const t = useT();
  return <Dialog title={t("接入配置")} icon={<IconPlug size={21}/>} onClose={onClose}>
    <div className={ui.form}>
      <ChannelIdentity name={value.name} providerName={value.providerName} providerCode={value.providerCode}/>
      <DetailSection title={t("应用信息")} icon={<IconBuilding size={19}/>}>
        <dl className={styles.configFacts}>
          <div><dt>{t("应用编号")}</dt><dd>{value.externalAppId}</dd></div>
          {value.externalTenantId && <div><dt>{t("企业编号")}</dt><dd>{value.externalTenantId}</dd></div>}
        </dl><p className={styles.secretNote}><IconKey size={16}/>{value.secretConfigured ? t("应用密钥已设置。") : t("尚未设置应用密钥。")}</p>
      </DetailSection>
      <DetailSection title={t("登录与授权地址")} icon={<IconPlug size={19}/>}
        action={<IntegrationSetupGuide providerCode={value.providerCode} connection={value} defaultTopic="addresses"/>}>
        <div className={ui.form}>{[{label: t("授权回调地址"), text: value.callbackUrl}, {label: t("企业登录链接"), text: value.loginUrl}].map(item =>
          <IntegrationAddressField key={item.label} label={item.label} value={item.text}/>)}</div>
      </DetailSection>
    </div><DialogActions><DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
  </Dialog>;
}
