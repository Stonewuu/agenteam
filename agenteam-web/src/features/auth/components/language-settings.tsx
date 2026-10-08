"use client";

import {Select} from "@/components/ui/select";
import {useId} from "react";
import {useLocale, useT} from "@/lib/i18n/locale-provider";
import {isLocale, locales} from "@/lib/i18n/locales";
import {loadIdentity} from "../api/identity-api";
import {useFormAction} from "../hooks/use-form-action";
import type {IdentityUser, Preferences} from "../types/identity";
import {FormFeedback} from "./settings-forms";
import styles from "./auth.module.css";
import settings from "./settings.module.css";

export function LanguageSettings({user, onChange}: { user: IdentityUser; onChange: (user: IdentityUser) => void }) {
  const t = useT();
  const {locale, setLocale} = useLocale();
  const action = useFormAction();
  const helpId = useId();
  return <section className={styles.section}>
    <h2>{t("语言")}</h2>
    <div className={styles.form}>
      <label className={styles.field}>
        <span className={styles.label}>{t("界面语言")}</span>
        <Select value={locale} name="interfaceLanguage" onChange={(event) => {
          if (isLocale(event.target.value)) {
            setLocale(event.target.value);
          }
        }}>
          {Object.entries(locales).map(([code, language]) => <option key={code} value={code}
                                                                     lang={code}>{language.name}</option>)}
        </Select>
      </label>
      <label className={styles.field}>
        <span className={styles.label}>{t("模型回复语言")}</span>
        <Select value={user.preferences.responseLanguage ?? "zh-CN"} name="responseLanguage"
                aria-label={t("模型回复语言")} aria-describedby={helpId} disabled={action.busy} onChange={(event) => {
          const responseLanguage = event.target.value;
          if (!isLocale(responseLanguage) || responseLanguage === user.preferences.responseLanguage) {
            return;
          }
          void action.execute(async () => {
            const preferences = await action.mutation.run<Preferences>("/api/v1/me/preferences", {
              method: "PATCH", revision: user.preferences.revision, body: {responseLanguage},
            });
            onChange({...user, preferences});
          }, t("回复语言已更新。"));
        }}>
          {Object.entries(locales).map(([code, language]) => <option key={code} value={code}
                                                                     lang={code}>{language.name}</option>)}
        </Select>
        <span id={helpId} className={settings.fieldHint}>{t("用于之后的智能体回复。")}</span>
      </label>
      <FormFeedback action={action} onReload={async () => {
        const latest = await loadIdentity();
        if (latest) {
          onChange(latest);
        }
      }}/>
    </div>
  </section>;
}
