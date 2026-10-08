"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {richTextPreview} from "@/components/ui/rich-text-document";

import Link from "next/link";
import {useState} from "react";
import {usePathname, useSearchParams} from "next/navigation";
import {Button} from "@/components/ui/button";
import {PageHeader} from "@/components/ui/page-header";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {Tabs} from "@/components/ui/tabs";
import {IconBuilding, IconCheck, IconShield, IconRefresh} from "@/components/ui/icons";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import type {EnterpriseContext} from "@/features/auth/types/identity";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import {AnnouncementDialog} from "@/features/announcement/components/announcement-dialog";
import {type Announcement, type AnnouncementPage, announcementTitle} from "@/features/announcement/types/announcement";
import type {NotificationPage as PageData} from "../types/notification";
import {notificationTarget} from "../lib/notification-target";
import {notificationContent} from "../lib/notification-presentation";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {notificationsChanged, useNotificationCenter} from "./notification-center-provider";
import {NotificationIcon} from "./notification-icon";
import styles from "./notification.module.css";
import announcementStyles from "@/features/announcement/components/announcement.module.css";
import ui from "@/components/ui/surface.module.css";

export function NotificationPage({enterpriseId}: { enterpriseId: string }) {
  const uiText = useT();
  return <EnterpriseGate enterpriseId={enterpriseId}>{({user, context}) => <PlatformShell user={user} context={context}
                                                                                          area="user"
                                                                                          title={uiText("通知与公告")}>
    <NotificationFeed key={enterpriseId} context={context}/>
  </PlatformShell>}</EnterpriseGate>;
}

function NotificationFeed({context}: { context: EnterpriseContext }) {
  const uiText = useT();
  const center = useNotificationCenter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get("tab") === "announcements" ? "announcements" : "notifications";
  const enterpriseId = context.enterprise.id;
  const [navigation, setNavigation] = useState<{
    tab: string; unread: boolean; cursor: string | null; previous: (string | null)[];
  }>({tab, unread: false, cursor: null, previous: []});
  // 保留页签组件以延续滑动动画，切换分类时只重置筛选和分页。
  const current = navigation.tab === tab ? navigation : {tab, unread: false, cursor: null, previous: []};
  const {unread, cursor, previous} = current;
  const [selected, setSelected] = useState<Announcement | null>(null);
  const action = useFormAction();
  const filter = `?unread=${unread}&limit=30${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`;
  const announcementList = useApiQuery<AnnouncementPage>(tab === "announcements" ? organizationPath(enterpriseId, `/announcements${filter}`) : null, center?.revision ?? 0);
  const notificationList = useApiQuery<PageData>(tab === "notifications" ? organizationPath(enterpriseId, `/notifications${filter}`) : null, center?.revision ?? 0);
  const list = tab === "announcements" ? announcementList : notificationList;
  const first = () => {
    setNavigation({...current, cursor: null, previous: []});
  };
  const readAll = () => {
    if (!list.data || list.loading) {
      return;
    }
    const throughSequence = list.data.throughSequence;
    void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, `/${tab}/read-all`), {
        method: "POST",
        body: {throughSequence}
      });
      first();
      notificationsChanged();
    }, uiText("已全部标为已读。"));
  };
  return <>
    <PageHeader title={uiText("通知与公告")} description={uiText("查看平台与企业公告，以及与你相关的工作提醒。")}
                actions={<div className={ui.actions}>
                  <Button className={ui.button} disabled={list.loading || action.busy} onClick={() => {
                    first();
                    notificationsChanged();
                  }}><IconRefresh size={16}/>{uiText("刷新")}</Button>
                  <Button className={ui.button}
                          disabled={list.loading || !list.data || list.data.throughSequence === "0" || action.busy}
                          onClick={readAll}><IconCheck size={16}/>{uiText("全部已读")}</Button>
                </div>}/>
    <Tabs value={tab} onChange={(value) => {
      setNavigation({tab: value, unread: false, cursor: null, previous: []});
      action.resetFeedback();
      const next = new URLSearchParams(params.toString());
      next.set("tab", value);
      window.history.replaceState(null, "", `${pathname}?${next}`);
    }} label={uiText("公告与通知")} items={[
      {
        value: "announcements",
        label: uiText("公告"),
        prefix: (center?.data?.announcements.count ?? 0) > 0 ?
          <span className={announcementStyles.dot} aria-label={uiText("有未读公告")}/> : undefined
      },
      {
        value: "notifications",
        label: uiText("通知"),
        prefix: (center?.data?.notifications.count ?? 0) > 0 ?
          <span className={announcementStyles.dot} aria-label={uiText("有未读通知")}/> : undefined
      },
    ]}/>
    <div className={announcementStyles.toolbar}><Tabs value={unread ? "unread" : "all"} onChange={(value) => {
      setNavigation({tab, unread: value === "unread", cursor: null, previous: []});
    }} variant="filter" label={uiText("阅读状态")} items={[{value: "all", label: uiText("全部")}, {
      value: "unread",
      label: uiText("未读")
    }]}/></div>
    <MutationFeedback action={action}/>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)}
                empty={tab === "announcements" ? unread ? uiText("暂无未读公告。") : uiText("暂无公告。") : unread ? uiText("暂无未读通知。") : uiText("暂无通知。")}>
      {tab === "announcements" ? <div className={announcementStyles.list}>{announcementList.data?.items.map((value) => {
        const Icon = value.scope === "platform" ? IconShield : IconBuilding;
        return <article className={`${announcementStyles.card} ${announcementStyles.interactiveCard}`}
                        key={`${value.id}:${value.version}`}>
          <span className={announcementStyles.scopeIcon}><Icon size={22}/></span>
          <div className={announcementStyles.cardContent}><Button
            className={`${announcementStyles.cardTitle} ${announcementStyles.cardOpen}`}
            onClick={() => setSelected(value)}>{announcementTitle(value, uiText)}</Button>
            <p className={announcementStyles.preview}>{richTextPreview(value)}</p>
            <div className={announcementStyles.metadata}><span>{value.level.name}</span>
              {value.publisherName && <span>{uiText("发布人：")}{value.publisherName}</span>}
              {value.publishedAt && <EnterpriseDateTime value={value.publishedAt}/>}{!value.readAt &&
                <span className={announcementStyles.dot} aria-label={uiText("未读")}/>}</div>
          </div>
          {!value.readAt && <Button className={`icon-button ${announcementStyles.cardReadAction}`}
                                    aria-label={uiText("将{0}标为已读", [value.title])} disabled={action.busy}
                                    onClick={() => void action.execute(async () => {
                                      await action.mutation.run(organizationPath(enterpriseId, `/announcements/${encodeURIComponent(value.id)}/read`), {
                                        method: "POST",
                                        body: {version: value.version}
                                      });
                                      notificationsChanged();
                                    }, "")}><IconCheck size={18}/></Button>}
        </article>;
      })}</div> : <div className={styles.list}>{notificationList.data?.items.map((value) => {
        const display = notificationContent(value, uiText);
        const href = notificationTarget(enterpriseId, value, context.permissions)
          ?? enterprisePath(enterpriseId, `/notifications/${encodeURIComponent(value.id)}`);
        const content = <><h2>{display.title}</h2><p>{display.body}</p></>;
        return <article className={styles.notice} key={value.id} data-unread={!value.readAt}>
          <span className={styles.categoryIcon}><NotificationIcon targetType={value.targetType} size={21}/></span>
          {href ? <Link className={styles.noticeContent} href={href}>{content}</Link> :
            <div className={styles.noticeContent}>{content}</div>}
          <div className={styles.noticeMeta}>{!value.readAt &&
            <span className={announcementStyles.dot} aria-label={uiText("未读")}/>}<EnterpriseDateTime
            value={value.createdAt} compact/>
            {!value.readAt &&
              <Button className="icon-button" aria-label={uiText("将{0}标为已读", [display.title])} disabled={action.busy}
                      onClick={() => void action.execute(async () => {
                        await action.mutation.run(organizationPath(enterpriseId, `/notifications/${encodeURIComponent(value.id)}/read`), {method: "POST"});
                        notificationsChanged();
                      }, "")}><IconCheck size={17}/></Button>}</div>
        </article>;
      })}</div>}
    </QueryState>
    <Pagination previous={previous} hasMore={list.data?.hasMore} loading={list.loading || action.busy}
                back={() => {
                  setNavigation({...current, cursor: previous.at(-1) ?? null, previous: previous.slice(0, -1)});
                }}
                next={() => {
                  if (list.data?.hasMore && list.data.nextCursor) {
                    setNavigation({...current, cursor: list.data.nextCursor, previous: [...previous, cursor]});
                  }
                }}/>
    {selected &&
      <AnnouncementDialog enterpriseId={enterpriseId} value={selected} onClose={() => setSelected(null)} onRead={() => {
        setSelected(null);
        notificationsChanged();
      }}/>}
  </>;
}
