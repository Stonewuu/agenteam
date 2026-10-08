"use client";

import {DetailHeading, SettingRow} from "@/components/ui/detail-section";
import {IconBell, IconCalendarClock, IconCheck, IconKey, IconRobot, IconShield, IconUsers} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./integration.module.css";
import {ChannelIdentity} from "./channel-identity";

const categoryIcons = {execution: IconRobot, approval: IconShield, permission: IconKey, hire: IconUsers, todo: IconCheck, schedule: IconCalendarClock};

type Preference = {
  connectionId: string; connectionName: string; providerName: string; canEnable: boolean;
  categories: {category: string; name: string; enabled: boolean; revision: string}[];
};

export function ChannelPreferencePanel({enterpriseId, refresh, busy, onUpdate}: {
  enterpriseId: string; refresh: number; busy: boolean;
  onUpdate: (connectionId: string, category: string, revision: string, enabled: boolean) => void;
}) {
  const t = useT();
  const preferences = useApiQuery<Preference[]>(`/api/v1/enterprises/${encodeURIComponent(enterpriseId)}/me/channel-preferences`, refresh);
  return <section className={styles.preferences}>
    <DetailHeading icon={<IconBell size={19}/>} title={t("自动通知")} description={t("选择希望通过企业账号接收的工作提醒。")}/>
    <QueryState {...preferences} hasData={Boolean(preferences.data?.length)} empty={t("绑定企业账号后，可在这里选择自动通知。")}>
      <div className={styles.list}>{preferences.data?.map((value) => <article className={styles.bindingCard} key={value.connectionId}>
        <div className={styles.bindingHeader}><ChannelIdentity compact name={value.connectionName} providerName={value.providerName}/></div>
        {!value.canEnable && <p className={styles.bindingNotice}>{t("此账号暂不能接收新通知，请检查绑定和接收设置。")}</p>}
        <div className={styles.preferenceGrid}>{value.categories.map((category) => {
          const Icon = categoryIcons[category.category as keyof typeof categoryIcons] ?? IconBell;
          return <SettingRow key={category.category} icon={<Icon size={18}/>} label={t(category.name)}
            checked={category.enabled} disabled={busy || !value.canEnable && !category.enabled}
            onChange={(checked) => onUpdate(value.connectionId, category.category, category.revision, checked)}/>;
        })}</div>
      </article>)}</div>
    </QueryState>
  </section>;
}
