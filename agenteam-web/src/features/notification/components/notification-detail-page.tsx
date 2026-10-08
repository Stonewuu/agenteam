"use client";

import Link from "next/link";
import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconBell, IconArrowLeft, IconArrowRight, IconCheck, IconClock} from "@/components/ui/icons";
import {DetailStatus} from "@/components/ui/detail-section";
import {PageHeader} from "@/components/ui/page-header";
import {QueryState} from "@/components/ui/query-state";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {Notification} from "../types/notification";
import {notificationTarget} from "../lib/notification-target";
import {notificationContent} from "../lib/notification-presentation";
import {ChannelDeliveryPanel} from "./channel-delivery-panel";
import {notificationsChanged} from "./notification-center-provider";
import styles from "./channel-delivery.module.css";
import ui from "@/components/ui/surface.module.css";

export function NotificationDetailPage({enterpriseId, notificationId}: {enterpriseId: string; notificationId: string}) {
  const t = useT();
  return <EnterpriseGate enterpriseId={enterpriseId}>{({user, context}) => <PlatformShell user={user} context={context} area="user" title={t("通知详情")}>
    <NotificationDetail enterpriseId={enterpriseId} notificationId={notificationId} permissions={context.permissions}/>
  </PlatformShell>}</EnterpriseGate>;
}

function NotificationDetail({enterpriseId, notificationId, permissions}: {enterpriseId: string; notificationId: string; permissions: string[]}) {
  const t = useT();
  const [refresh, setRefresh] = useState(0);
  const path = `/api/v1/enterprises/${encodeURIComponent(enterpriseId)}/notifications/${encodeURIComponent(notificationId)}`;
  const query = useApiQuery<Notification>(path, refresh);
  const action = useFormAction();
  const value = query.data;
  const content = value ? notificationContent(value, t) : null;
  const target = value ? notificationTarget(enterpriseId, value, permissions) : null;
  return <div className={styles.page}>
    <PageHeader title={t("通知详情")} icon={<IconBell size={24} variant="Bulk"/>}
      actions={<Link className={ui.button} href={enterprisePath(enterpriseId, "/notifications")}><IconArrowLeft size={16}/>{t("返回通知列表")}</Link>}/>
    <QueryState {...query} hasData={Boolean(value)} empty={t("通知不存在或已无法访问。")}>
      {value && content && <div className={styles.content}>
        <article className={styles.notice}><div className={styles.heading}><span className={styles.noticeIcon}><IconBell size={23}/></span>
          <DetailStatus tone={value.readAt ? "neutral" : "accent"}>{value.readAt ? t("已读") : t("未读")}</DetailStatus></div>
          <h2>{content.title}</h2><p className={styles.meta}><IconClock size={15}/><EnterpriseDateTime value={value.createdAt}/></p>
          {content.body && <p className={styles.body}>{content.body}</p>}
        {(target || !value.readAt) && <div className={`${ui.actions} ${styles.noticeActions}`}>
          {target && <Link className={ui.primary} href={target}>{t("查看相关内容")}<IconArrowRight size={16}/></Link>}
          {!value.readAt && <Button className={ui.button} disabled={action.busy} onClick={() => void action.execute(async () => {
            await action.mutation.run(`${path}/read`, {method: "POST"});
            notificationsChanged();
            setRefresh((current) => current + 1);
          }, "")}><IconCheck size={16}/>{t("标为已读")}</Button>}
        </div>}</article>
        <section className={styles.deliverySection}><ChannelDeliveryPanel enterpriseId={enterpriseId} notificationId={notificationId} showHeading
          initialDeliveryId={value.targetType === "notification_delivery" ? value.targetId ?? undefined : undefined}/></section>
      </div>}
    </QueryState>
    <MutationFeedback action={action}/>
  </div>;
}
