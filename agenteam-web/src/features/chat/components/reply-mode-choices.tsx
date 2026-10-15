"use client";

import {useId} from "react";
import {Fieldset} from "@/components/ui/fieldset";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";
import {IconExternalLink, IconMessages, IconLock} from "@/components/ui/icons";
import {RadioGroup, RadioGroupItem} from "@/components/ui/radio-group";
import {useT} from "@/lib/i18n/locale-provider";
import type {ReplyMode, ReplyPreference} from "../types/reply-preferences";
import styles from "./reply-preferences.module.css";

export function ReplySource({value}: {value: ReplyPreference}) {
  const t = useT();
  return <p className={styles.source}><IconMessages size={17}/><span>
    {t(value.platform === "feishu" ? "飞书" : "企业微信")} · {value.kind === "direct"
      ? value.botNames?.length ? t("与{0}私聊", [value.botNames.join("、")]) : t("机器人私聊") : value.name || t("群聊")}
  </span></p>;
}

export function ReplyModeChoices({value, onChange, fullAllowed, disabled, error}: {
  value: ReplyMode; onChange: (value: ReplyMode) => void; fullAllowed: boolean; disabled?: boolean; error?: string;
}) {
  const t = useT();
  const id = useId();
  const describedBy = [!fullAllowed && `${id}-restriction`, error && `${id}-error`].filter(Boolean).join(" ") || undefined;
  return <Fieldset className={styles.options} disabled={disabled}>
    <legend className={styles.legend}>{t("回复方式")}</legend>
    {!fullAllowed && <p id={`${id}-restriction`} className={styles.restriction}><IconLock size={16}/>{t("本群已设为仅发送链接")}</p>}
    <RadioGroup aria-label={t("回复方式")} value={value} onValueChange={(next) => onChange(next as ReplyMode)} disabled={disabled}
      aria-describedby={describedBy} aria-invalid={Boolean(error)} className={styles.choices}>
      <label className={styles.choice} data-selected={value === "chat_full"} data-disabled={!fullAllowed}>
        <RadioGroupItem value="chat_full" disabled={!fullAllowed}/><IconMessages size={20}/>
        <span><strong>{t("直接回复内容")}</strong><small>{t("在原会话查看问题、回答和成果附件。")}</small></span>
      </label>
      <label className={styles.choice} data-selected={value === "summary_link"}>
        <RadioGroupItem value="summary_link"/><IconExternalLink size={20}/>
        <span><strong>{t("仅发送链接")}</strong><small>{t("在原会话接收提醒，打开群策查看和处理。")}</small></span>
      </label>
    </RadioGroup>
    <FieldErrorFeedback id={`${id}-error`} messages={error ? [error] : []}/>
  </Fieldset>;
}
