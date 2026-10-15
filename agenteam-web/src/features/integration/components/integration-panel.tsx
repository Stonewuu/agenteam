"use client";

import {type ReactNode, useState} from "react";
import {PageHeader} from "@/components/ui/page-header";
import {Tabs} from "@/components/ui/tabs";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {IconBuilding, IconMessages, IconPlug, IconRobot} from "@/components/ui/icons";
import {ChatAdminPanel} from "@/features/chat/components/chat-admin-panel";
import {useT} from "@/lib/i18n/locale-provider";
import {IntegrationConnections} from "./integration-connections";
import styles from "./integration.module.css";

export function IntegrationPanel({enterpriseId, system = false, canManage = true, canTest = true, canViewDeliveries = true, enterpriseSelector}: {
  enterpriseId: string; system?: boolean; canManage?: boolean; canTest?: boolean; canViewDeliveries?: boolean; enterpriseSelector?: ReactNode;
}) {
  const t = useT();
  const [section, setSection] = useState<"enterprise" | "bots">("enterprise");
  const [platform, setPlatform] = useState<"feishu" | "wecom">("feishu");
  return <div className={styles.adminPage}>
    <PageHeader title={t("企业协作平台")} icon={<IconPlug size={24} variant="Bulk"/>}
      description={t("配置企业账号与通知，管理智能体使用的飞书和企微机器人。")}/>
    {enterpriseSelector && <div className={styles.enterpriseToolbar}>{enterpriseSelector}</div>}
    <div className={styles.platformNavigation}><Tabs value={section} onChange={setSection} label={t("企业平台设置")} items={[
      {value: "enterprise", label: t("企业接入"), icon: IconBuilding}, {value: "bots", label: t("岗位机器人"), icon: IconRobot},
    ]}/></div>
    <AnimatedHeight preserveControlShadows>
      {section === "enterprise" ? <div className={styles.platformContent}>
        <Tabs value={platform} onChange={setPlatform} label={t("企业平台")} variant="filter" items={[
          {value: "feishu", label: t("飞书"), icon: IconMessages}, {value: "wecom", label: t("企业微信"), icon: IconBuilding},
        ]}/>
        <IntegrationConnections key={`${enterpriseId}:${platform}`} enterpriseId={enterpriseId} system={system} canManage={canManage}
          canTest={canTest} canViewDeliveries={canViewDeliveries} providerCode={platform}/>
      </div> : <ChatAdminPanel key={enterpriseId} enterpriseId={enterpriseId} system={system} canManage={canManage} canTest={canTest}/>}
    </AnimatedHeight>
  </div>;
}
