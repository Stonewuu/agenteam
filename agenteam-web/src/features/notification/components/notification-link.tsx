"use client";

import ui from "@/components/ui/surface.module.css";

import {useT} from "@/lib/i18n/locale-provider";

import {richTextPreview} from "@/components/ui/rich-text-document";

import Link from "next/link";
import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconBell, IconBuilding, IconCheck, IconShield} from "@/components/ui/icons";
import {Popover, PopoverContent, PopoverTrigger} from "@/components/ui/shadcn/popover";
import {QueryState} from "@/components/ui/query-state";
import {Tabs} from "@/components/ui/tabs";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {AnnouncementDialog} from "@/features/announcement/components/announcement-dialog";
import {type Announcement, type AnnouncementPage, announcementTitle} from "@/features/announcement/types/announcement";
import type {NotificationPage} from "../types/notification";
import {notificationTarget} from "../lib/notification-target";
import {notificationContent} from "../lib/notification-presentation";
import {notificationsChanged, useNotificationCenter} from "./notification-center-provider";
import {NotificationIcon} from "./notification-icon";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import styles from "./notification-center.module.css";

export function NotificationLink({enterpriseId, className}: { enterpriseId: string; className?: string }) {
  const uiText = useT();
  const center = useNotificationCenter();
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<"announcements" | "notifications">("announcements");
  const [selected, setSelected] = useState<Announcement | null>(null);
  const action = useFormAction();
  const revision = center?.revision ?? 0;
  const announcements = useApiQuery<AnnouncementPage>(open && tab === "announcements" ? organizationPath(enterpriseId, "/announcements?unread=true&limit=5") : null, revision);
  const notifications = useApiQuery<NotificationPage>(open && tab === "notifications" ? organizationPath(enterpriseId, "/notifications?unread=true&limit=5") : null, revision);
  const data = center?.data;
  const count = data ? data.announcements.count + data.notifications.count : null;
  const readAll = () => {
    if (!data) {
      return;
    }
    void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, "/notifications/center/read-all"), {
        method: "POST", body: {
          notificationThroughSequence: data.notifications.throughSequence,
          announcementThroughSequence: data.announcements.throughSequence,
        }
      });
      notificationsChanged();
    }, uiText("已全部标为已读。"));
  };
  return <>
    <Popover open={open} onOpenChange={(next) => {
      setOpen(next);
      if (next) {
        setTab(data?.announcements.count || !data?.notifications.count ? "announcements" : "notifications");
        center?.changed();
      }
    }}>
      <PopoverTrigger render={<Button className={className}
                                      aria-label={count === null ? uiText("通知与公告") : count > 0 ? uiText("通知与公告，{0} 条未读", [count]) : uiText("通知与公告，没有未读")}/>}>
        <IconBell size={20}/>{count !== null && count > 0 && <span className={`notification-dot ${styles.dot}`}/>}
      </PopoverTrigger>
      <PopoverContent align="end" sideOffset={12} className={styles.popover}
                      style={{width: "min(420px, calc(100vw - 24px))"}}>
        <div className={styles.heading}><h2>{uiText("通知与公告")}</h2><Button className={ui.button}
          disabled={!data || count === 0 || action.busy} onClick={readAll}><IconCheck size={16}/>{uiText("全部已读")}
        </Button></div>
        <Tabs value={tab} onChange={setTab} label={uiText("公告与通知")} items={[
          {
            value: "announcements",
            label: uiText("公告"),
            prefix: data && data.announcements.count > 0 ?
              <span className={styles.dot} aria-label={uiText("有未读公告")}/> : undefined
          },
          {
            value: "notifications",
            label: uiText("通知"),
            prefix: data && data.notifications.count > 0 ?
              <span className={styles.dot} aria-label={uiText("有未读通知")}/> : undefined
          },
        ]}/>
        <MutationFeedback action={action}/>
        <div className={styles.items}>
          {tab === "announcements" ? <QueryState {...announcements} hasData={Boolean(announcements.data?.items.length)}
                                                 empty={uiText("暂无未读公告。")}>
            {announcements.data?.items.slice(0, 5).map((value) => {
              const Icon = value.scope === "platform" ? IconShield : IconBuilding;
              return <div className={styles.item} key={`${value.id}:${value.version}`}>
                <Icon size={20}/><Button className={styles.content} onClick={() => {
                setOpen(false);
                setSelected(value);
              }}>
                <strong>{announcementTitle(value, uiText)}</strong><span>{richTextPreview(value)}</span></Button>
                <Button className="icon-button" aria-label={uiText("将{0}标为已读", [value.title])}
                        disabled={action.busy} onClick={() => void action.execute(async () => {
                  await action.mutation.run(organizationPath(enterpriseId, `/announcements/${encodeURIComponent(value.id)}/read`), {
                    method: "POST",
                    body: {version: value.version}
                  });
                  notificationsChanged();
                }, "")}><IconCheck size={16}/></Button>
              </div>;
            })}
          </QueryState> : <QueryState {...notifications} hasData={Boolean(notifications.data?.items.length)}
                                      empty={uiText("暂无未读通知。")}>
            {notifications.data?.items.slice(0, 5).map((value) => {
              const display = notificationContent(value, uiText);
              const target = notificationTarget(enterpriseId, value, center?.permissions ?? []);
              const content = <><strong>{display.title}</strong><span>{display.body}</span></>;
              return <div className={styles.item} key={value.id}><NotificationIcon targetType={value.targetType}/>
                {target ?
                  <Link className={styles.content} href={target} onClick={() => setOpen(false)}>{content}</Link> :
                  <div className={styles.content}>{content}</div>}
                <Button className="icon-button" aria-label={uiText("将{0}标为已读", [display.title])}
                        disabled={action.busy} onClick={() => void action.execute(async () => {
                  await action.mutation.run(organizationPath(enterpriseId, `/notifications/${encodeURIComponent(value.id)}/read`), {method: "POST"});
                  notificationsChanged();
                }, "")}><IconCheck size={16}/></Button>
              </div>;
            })}
          </QueryState>}
        </div>
        <Link className={styles.more} href={enterprisePath(enterpriseId, `/notifications?tab=${tab}`)}
              onClick={() => setOpen(false)}>{tab === "announcements" ? uiText("查看全部公告") : uiText("查看全部通知")}</Link>
      </PopoverContent>
    </Popover>
    {selected &&
      <AnnouncementDialog enterpriseId={enterpriseId} value={selected} onClose={() => setSelected(null)} onRead={() => {
        setSelected(null);
        notificationsChanged();
      }}/>}
  </>;
}
