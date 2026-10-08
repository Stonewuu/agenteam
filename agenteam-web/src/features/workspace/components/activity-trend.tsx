"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {type CSSProperties, useEffect, useId, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {QueryState} from "@/components/ui/query-state";
import {IconCalendar, IconChartBar, IconCheckbox, IconMessages, IconRefresh, IconUsers} from "@/components/ui/icons";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useApiQuery} from "@/lib/http/use-api-query";
import {
  activityCalendar,
  activityLevel,
  activityTotal,
  calendarDate,
  nextActivityIndex,
  selectedActivityIndex
} from "../lib/activity-calendar";
import type {ActivityTrend as ActivityTrendData} from "../types/activity";
import styles from "./activity-trend.module.css";

const categories = [
  {key: "conversations", label: "对话", icon: IconMessages},
  {key: "schedules", label: "定时任务", icon: IconCalendar},
  {key: "todos", label: "待办", icon: IconCheckbox},
  {key: "employees", label: "员工雇佣", icon: IconUsers},
] as const;

export function ActivityTrend({enterprise, refresh}: { enterprise: string; refresh: string | number }) {
  const uiText = useT();
  const [revision, setRevision] = useState(0);
  const query = useApiQuery<ActivityTrendData>(organizationPath(enterprise, "/home/activity"), `${refresh}:${revision}`);
  useEffect(() => {
    const update = () => setRevision((value) => value + 1);
    const visible = () => {
      if (document.visibilityState === "visible") {
        update();
      }
    };
    window.addEventListener("focus", update);
    document.addEventListener("visibilitychange", visible);
    // 长时间停留时更新日期；离开页面后立即停止。
    const timer = window.setInterval(visible, 60_000);
    return () => {
      window.removeEventListener("focus", update);
      document.removeEventListener("visibilitychange", visible);
      window.clearInterval(timer);
    };
  }, []);
  return <section className={styles.card} aria-label={uiText("我的活动趋势")}>
    <header className={styles.header}>
      <div className={styles.heading}><span className={styles.headingIcon}><IconChartBar size={20}
                                                                                         variant="Bulk"/></span>
        <div><h2>{uiText("活动足迹")}</h2><p>{uiText("每一天，留下一点进展")}</p></div>
      </div>
      <div className={styles.headerActions}><span>{uiText("过去一年")}</span><Button type="button"
                                                                                     className="icon-button"
                                                                                     aria-label={uiText("刷新活动趋势")}
                                                                                     disabled={query.loading}
                                                                                     onClick={query.retry}><IconRefresh
        size={16}/></Button></div>
    </header>
    <QueryState {...query} loading={query.loading && !query.data} hasData={Boolean(query.data?.days.length)}
                empty={uiText("暂时没有活动记录。")} contentLayout="flow"
                loadingContent={<div className={styles.skeleton} role="status" aria-label={uiText("正在加载活动趋势")}>
                  <div/>
                  <div/>
                  <div/>
                </div>}>
      {query.data && <ActivityCalendar data={query.data}/>}
    </QueryState>
  </section>;
}

function ActivityCalendar({data}: { data: ActivityTrendData }) {
  const uiText = useT();
  const dateFormat = new Intl.DateTimeFormat(uiText.formatLocale, {
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "long",
    timeZone: "UTC"
  });
  const {days} = data;
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [hoveredDate, setHoveredDate] = useState<string | null>(null);
  const selected = selectedActivityIndex(days, selectedDate);
  const hovered = hoveredDate ? days.findIndex((day) => day.date === hoveredDate) : -1;
  const scroller = useRef<HTMLDivElement>(null);
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);
  const helpId = useId();
  const {firstWeekday, weekCount, months} = activityCalendar(days, uiText.formatLocale);
  const maximum = Math.max(1, ...days.map(activityTotal));
  const total = days.reduce((sum, day) => sum + activityTotal(day), 0);
  const current = days[hovered >= 0 ? hovered : selected];
  useEffect(() => {
    const viewport = scroller.current;
    if (!viewport) {
      return;
    }
    const revealSelection = () => {
      const button = buttons.current[selected];
      if (!button) {
        return;
      }
      const box = button.getBoundingClientRect();
      const frame = viewport.getBoundingClientRect();
      if (box.left < frame.left + 4) {
        viewport.scrollLeft += box.left - frame.left - 4;
      } else if (box.right > frame.left + viewport.clientWidth - 4) {
        viewport.scrollLeft += box.right - frame.left - viewport.clientWidth + 4;
      }
    };
    // 手机旋转或窗口缩小时，只调整图内横向位置，不改变页面滚动。
    const observer = new ResizeObserver(revealSelection);
    observer.observe(viewport);
    revealSelection();
    return () => observer.disconnect();
  }, [selected]);
  return <>
    <div className={styles.summary}>
      <strong>{total.toLocaleString(uiText.formatLocale)}<span>{uiText("次活动")}</span></strong>
      <p>{total ? uiText("与数字员工协作，也把日常工作一步步推进。") : uiText("从一次对话或一项待办开始，记录你的工作日常。")}</p>
    </div>
    <p id={helpId}
       className="sr-only">{uiText("每个格子代表一天，颜色越深活动越多。点击查看当天次数。方向键选择日期，Home 跳到第一天，End 跳到最近一天。")}</p>
    <div className={styles.calendar}>
      <div className={styles.weekdays} aria-hidden="true">
        <span>{uiText("一")}</span><span>{uiText("三")}</span><span>{uiText("五")}</span><span>{uiText("日")}</span>
      </div>
      <div ref={scroller} className={styles.scroller}>
        <div className={styles.plot} style={{"--weeks": weekCount} as CSSProperties}>
          <div className={styles.months} aria-hidden="true">{months.map((month) => <span
            key={`${month.column}:${month.label}`}
            style={{gridColumn: `${month.column} / span ${Math.min(3, weekCount - month.column + 1)}`}}>{month.label}</span>)}</div>
          <div className={styles.days} role="group" aria-label={uiText("每日活动")} aria-describedby={helpId}
               onPointerLeave={() => setHoveredDate(null)}>
            {days.map((day, index) => {
              const count = activityTotal(day);
              const label = uiText("{0}，{1} 次活动", [dateFormat.format(calendarDate(day.date)), count]);
              return <button key={day.date} type="button" ref={(node) => {
                buttons.current[index] = node;
              }} className={styles.day} data-level={activityLevel(count, maximum)} aria-label={label} title={label}
                             aria-pressed={selected === index} tabIndex={selected === index ? 0 : -1}
                             style={{
                               gridColumn: Math.floor((firstWeekday + index) / 7) + 1,
                               gridRow: ((firstWeekday + index) % 7) + 1
                             }}
                             onPointerEnter={(event) => {
                               if (event.pointerType === "mouse") {
                                 setHoveredDate(day.date);
                               }
                             }} onFocus={() => {
                setSelectedDate(day.date);
                setHoveredDate(null);
              }} onClick={() => {
                setSelectedDate(day.date);
                setHoveredDate(null);
              }} onKeyDown={(event) => {
                if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) {
                  event.preventDefault();
                  buttons.current[nextActivityIndex(index, event.key, days.length)]?.focus({preventScroll: true});
                  buttons.current[nextActivityIndex(index, event.key, days.length)]?.scrollIntoView({
                    block: "nearest",
                    inline: "nearest"
                  });
                }
              }}/>;
            })}
          </div>
        </div>
      </div>
    </div>
    <div className={styles.legendRow}><span
      className={styles.range}>{data.startDate.replaceAll("-", ".")} — {data.endDate.replaceAll("-", ".")}</span><span
      className={styles.mobileHint}>{uiText("左右滑动查看全年")}</span>
      <div className={styles.legend} aria-label={uiText("颜色越深活动越多")}>
        <span>{uiText("少")}</span>{[0, 1, 2, 3, 4].map((level) => <i key={level}
                                                                      data-level={level}/>)}<span>{uiText("多")}</span>
      </div>
    </div>
    {current && <div className={styles.details} aria-live="polite" aria-atomic="true">
      <div className={styles.selectedDate}><IconCalendar
        size={16}/><span>{dateFormat.format(calendarDate(current.date))}</span><strong>{activityTotal(current)}{uiText(" 次")}</strong>
      </div>
      <div className={styles.categories}>{localizeCatalog(categories, uiText).map(({key, label, icon: Icon}) => <span
        key={key}><Icon size={14}/>{label}<b>{current[key]}</b></span>)}</div>
    </div>}
  </>;
}
