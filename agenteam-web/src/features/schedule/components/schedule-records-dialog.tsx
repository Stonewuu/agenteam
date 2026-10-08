"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {timezoneLabel} from "@/lib/timezones";
import {useEffect, useState} from "react";
import Link from "next/link";
import {Dialog} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import type {Occurrence, Schedule} from "../types/schedule";
import {OccurrenceCard} from "./occurrence-card";
import {IconHistory, IconArrowRight, IconClock} from "@/components/ui/icons";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule.module.css";

export function ScheduleRecordsDialog({enterpriseId, scheduleId, permissions, onClose}: {
  enterpriseId: string;
  scheduleId: string;
  permissions: string[];
  onClose: () => void
}) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const root = organizationPath(enterpriseId, `/schedules/${encodeURIComponent(scheduleId)}`);
  const plan = useApiQuery<Schedule>(root, refresh);
  const list = useApiPage<Occurrence>(`${root}/occurrences`, refresh, 0);
  const changed = () => setRefresh((value) => value + 1);
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        setRefresh((value) => value + 1);
      }
    }, 10000);
    return () => window.clearInterval(timer);
  }, []);
  return <Dialog title={plan.data ? uiText("{0} · 执行记录", [plan.data.name]) : uiText("执行记录")} icon={<IconHistory size={21}/>} onClose={onClose}
                 drawer>
    <div className={ui.form}><Link className={ui.button}
                                   href={enterprisePath(enterpriseId, `/schedules/${encodeURIComponent(scheduleId)}`)}>{uiText("查看计划详情")}<IconArrowRight size={16}/></Link>
      <QueryState {...plan} hasData={Boolean(plan.data)} empty={uiText("计划暂不可查看。")}>{plan.data && <><p
        className={styles.rule}><IconClock size={15}/>{timezoneLabel(plan.data.timezone, uiText)}</p>
        <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("尚无执行记录。")}>
          <div className={styles.list}>{list.data?.items.map((item) => <OccurrenceCard key={item.id}
                                                                                       enterpriseId={enterpriseId}
                                                                                       value={item}
                                                                                       timezone={plan.data!.timezone}
                                                                                       active={plan.data!.activeOccurrenceId === item.id}
                                                                                       permissions={permissions}
                                                                                       onChanged={changed}/>)}</div>
        </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
      </>}</QueryState>
    </div>
  </Dialog>;
}
