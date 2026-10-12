"use client";

import {Select} from "./select";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./date-time-input.module.css";

const options = (count: number) => Array.from({length: count}, (_, i) => String(i).padStart(2, "0"));
const hours = options(24);
const minutes = options(60);

export function TimePickerFields({value, onChange, seconds}: {value: string; onChange: (value: string) => void; seconds: boolean}) {
  const t = useT();
  const parts = value.split(":");
  function change(index: number, next: string) {
    const result = [parts[0] || "00", parts[1] || "00", parts[2] || "00"];
    result[index] = next;
    onChange(result.slice(0, seconds ? 3 : 2).join(":"));
  }
  return <div className={styles.timeFields}>
    <label><span>{t("时")}</span><Select aria-label={t("小时")} value={parts[0] || "00"} onChange={(event) => change(0, event.target.value)}>
      {hours.map((value) => <option key={value} value={value}>{value}</option>)}
    </Select></label>
    <label><span>{t("分")}</span><Select aria-label={t("分钟")} value={parts[1] || "00"} onChange={(event) => change(1, event.target.value)}>
      {minutes.map((value) => <option key={value} value={value}>{value}</option>)}
    </Select></label>
    {seconds && <label><span>{t("秒")}</span><Select aria-label={t("秒钟")} value={parts[2]?.split(".")[0] || "00"} onChange={(event) => change(2, event.target.value)}>
      {minutes.map((value) => <option key={value} value={value}>{value}</option>)}
    </Select></label>}
  </div>;
}
