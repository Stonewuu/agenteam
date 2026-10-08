"use client";
import { useT } from "@/lib/i18n/locale-provider";
import { IconChartBar, IconClock, IconStack } from "@/components/ui/icons";
import { scheduleTime } from "@/features/schedule/lib/schedule-display";
import type { Usage } from "../types/usage";
import styles from "./usage.module.css";

export function UsageOverview({ usage }: { usage: Usage }) {
  const uiText = useT();
  const count = (value: number | null) => (value === null ? uiText("不限") : value.toLocaleString(uiText.formatLocale));
  const root = usage.items.find((item) => item.subjectType === "enterprise");
  return (
    <>
      {root && (
        <section aria-label={uiText("企业本周期用量")} className={styles.summary}>
          {[
            { title: uiText("本月已使用"), value: root.usedCount, icon: IconChartBar },
            {
              title: uiText("已预留"),
              value: root.reservedCount,
              icon: IconClock,
            },
            { title: uiText("剩余次数"), value: root.remainingCount, icon: IconStack },
          ].map(({ title, value, icon: Icon }) => (
            <div key={title}>
              <div className={styles.statLabel}>
                <span>{title}</span>
                <Icon size={20} />
              </div>
              <strong>{count(value)}</strong>
              <small>{value === null ? uiText("不设上限") : uiText("次执行")}</small>
            </div>
          ))}
        </section>
      )}
      {root && (
        <section className={styles.monthly}>
          <div className={styles.monthlyHeading}>
            <h2>{uiText("企业月度用量")}</h2>
            <span
              title={uiText("{0} 至 {1} · {2}", [
                scheduleTime(usage.periodStart, usage.timezone, uiText.formatLocale),
                scheduleTime(usage.periodEnd, usage.timezone, uiText.formatLocale),
                usage.timezone,
              ])}
            >
              {new Date(usage.periodStart).toLocaleDateString(uiText.formatLocale, {
                timeZone: usage.timezone,
                year: "numeric",
                month: "long",
              })}
            </span>
          </div>
          {root.monthlyLimit !== null && root.monthlyLimit > 0 && (
            <div
              className={styles.progress}
              role="progressbar"
              aria-label={uiText("本月已使用次数")}
              aria-valuemin={0}
              aria-valuemax={root.monthlyLimit}
              aria-valuenow={Math.min(root.usedCount, root.monthlyLimit)}
              aria-valuetext={uiText("已使用 {0} 次，上限 {1} 次", [root.usedCount, root.monthlyLimit])}
            >
              <span style={{ width: `${Math.min(100, (root.usedCount / root.monthlyLimit) * 100)}%` }} />
            </div>
          )}
          <div className={styles.monthlyFooter}>
            <span>
              {uiText("已使用 ")}
              {count(root.usedCount)}
              {uiText(" 次")}
            </span>
            <span>
              {uiText("月度上限 ")}
              {count(root.monthlyLimit)}
              {root.monthlyLimit !== null ? uiText(" 次") : ""}
            </span>
          </div>
        </section>
      )}
    </>
  );
}
