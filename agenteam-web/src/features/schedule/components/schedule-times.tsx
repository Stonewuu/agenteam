"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {DetailSection} from "@/components/ui/detail-section";
import {IconCalendarClock, IconClock} from "@/components/ui/icons";

import {timezoneLabel} from "@/lib/timezones";
import type {ScheduleTimes} from "../types/schedule";
import {scheduleTime} from "../lib/schedule-display";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule-presentation.module.css";

export function ScheduleTimesPreview({value}: { value: ScheduleTimes }) {
  const uiText = useT();
  return <DetailSection title={uiText("接下来的执行时间")} icon={<IconCalendarClock size={19}/>}
    description={timezoneLabel(value.timezone, uiText)}>
    {value.times.length ? <ol className={styles.timeList}>{value.times.map((time, index) => <li key={time}>
      <IconClock size={15}/><time dateTime={time}>{scheduleTime(time, value.timezone, uiText.formatLocale)}</time>
      {index === 0 && <small>{uiText("下一次")}</small>}
    </li>)}</ol> : <p className={ui.description}>{uiText("当前规则没有后续执行时间。")}</p>}
    {value.warnings.map((warning) => <p className={ui.notice} key={warning}>{warning}</p>)}
  </DetailSection>;
}
