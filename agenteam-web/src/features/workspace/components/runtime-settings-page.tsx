"use client";

import {useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {DetailSection} from "@/components/ui/detail-section";
import {IconAdjustments} from "@/components/ui/icons";
import {LimitField, UNLIMITED_VALUE} from "@/components/ui/limit-field";
import {PageHeader} from "@/components/ui/page-header";
import {QueryState} from "@/components/ui/query-state";
import {Tabs} from "@/components/ui/tabs";
import {SystemManagementGate} from "@/features/auth/components/system-management-gate";
import {FormFeedback} from "@/features/auth/components/settings-forms";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {useBlockNavigation} from "./navigation-guard";
import {apiRequest} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {localizeUiMessage} from "@/lib/i18n/ui-message";
import {runtimeFields, runtimeSections} from "../lib/runtime-settings";
import type {RuntimeSection, RuntimeSettings} from "../types/runtime-settings";
import styles from "./runtime-settings.module.css";
import ui from "@/components/ui/surface.module.css";

const endpoint = "/api/v1/system/runtime-settings";
const draftOf = (value: RuntimeSettings) => Object.fromEntries(
  Object.entries(value.values).map(([key, number]) => [key, String(number)]));

export function RuntimeSettingsPage() {
  const t = useT();
  return <SystemManagementGate title={t("运行设置")}>{() => <RuntimeSettingsContent/>}</SystemManagementGate>;
}

function RuntimeSettingsContent() {
  const t = useT();
  const query = useApiQuery<RuntimeSettings>(endpoint);
  return <div className={styles.page}>
    <PageHeader title={t("运行设置")} icon={<IconAdjustments size={26} variant="Bulk"/>}
      description={t("管理任务执行、运行环境和记录保留。")}/>
    <QueryState {...query} hasData={Boolean(query.data)} empty={t("暂时无法读取运行设置。")}
      emptyContent={<Button className={ui.button} onClick={query.retry}>{t("重新加载")}</Button>}>
      {query.data && <RuntimeSettingsForm initial={query.data}/>}
    </QueryState>
  </div>;
}

function RuntimeSettingsForm({initial}: {initial: RuntimeSettings}) {
  const t = useT();
  const [section, setSection] = useState<RuntimeSection>("execution");
  const [saved, setSaved] = useState(initial);
  const [draft, setDraft] = useState(() => draftOf(initial));
  const [finiteValues, setFiniteValues] = useState<Record<string, string>>({});
  const [clientErrors, setClientErrors] = useState<Record<string, string[]>>({});
  const action = useFormAction();
  const current = runtimeSections.find(value => value.id === section)!;
  const keys = current.groups.flatMap(group => group.keys);
  const changed = (key: string) => draft[key] !== String(saved.values[key]);
  const dirty = Object.keys(draft).some(changed);
  const sectionDirty = keys.some(changed);
  useBlockNavigation(dirty, action.busy);
  const fieldErrors = {...action.fieldErrors, ...clientErrors};

  function clearFieldError(name: string) {
    setClientErrors(previous => Object.fromEntries(Object.entries(previous).filter(([key]) => key !== name)));
    action.clearFieldError(name);
  }

  function accept(value: RuntimeSettings, acceptedKeys: string[]) {
    setDraft(previous => Object.fromEntries(Object.entries(value.values).map(([key, number]) => [
      key, acceptedKeys.includes(key) || previous[key] === String(saved.values[key]) ? String(number) : previous[key],
    ])));
    setSaved(value);
  }

  return <div className={styles.layout}>
    <aside className={styles.categories} aria-label={t("设置分类")}>
      <Tabs value={section} orientation="vertical" variant="navigation" label={t("设置分类")}
        items={runtimeSections.map(value => {
          const changes = value.groups.flatMap(group => group.keys).filter(changed).length;
          return {value: value.id, label: t(value.label), icon: value.icon, count: changes || undefined};
        })} onChange={next => {
          if (action.busy) {
            return;
          }
          setSection(next);
          setClientErrors({});
          action.resetFeedback();
        }}/>
    </aside>
    <div className={styles.content}>
      <AnimatedHeight preserveControlShadows>
        <form className={styles.form} aria-label={t(current.label)} noValidate onSubmit={event => {
          event.preventDefault();
          if (!sectionDirty) {
            return;
          }
          const errors: Record<string, string[]> = {};
          for (const key of keys) {
            const bounds = saved.bounds[key];
            if (!draft[key].trim()) {
              errors["values." + key] = [t("请填写{0}。", [t(runtimeFields[key].label)])];
            } else if (!Number.isSafeInteger(Number(draft[key])) || Number(draft[key]) !== UNLIMITED_VALUE && Number(draft[key]) < bounds.minimum) {
              errors["values." + key] = [t("请输入不小于 {0} 的整数，或选择不限。", [bounds.minimum])];
            }
          }
          setClientErrors(errors);
          if (Object.keys(errors).length) {
            action.resetFeedback();
            return;
          }
          const changedKeys = keys.filter(changed);
          void action.execute(async () => {
            const result = await action.mutation.run<RuntimeSettings>(endpoint + "/" + section, {
              method: "PUT", revision: saved.revision,
              body: {values: Object.fromEntries(changedKeys.map(key => [key, Number(draft[key])]))},
            });
            accept(result, keys);
          }, t("运行设置已保存。"));
        }}>
          <header className={styles.intro}>
            <h2>{t(current.label)}</h2>
            <p>{t(current.description)}</p>
          </header>
          {current.groups.map(group => <DetailSection key={group.title} icon={<current.icon size={21}/>}
            title={t(group.title)}>
            <div className={styles.fields}>
              {group.keys.map(key => {
                const definition = runtimeFields[key];
                const bounds = saved.bounds[key];
                const name = "values." + key;
                const error = fieldErrors[name]?.map(message => localizeUiMessage(message, t)).join(" ");
                return <LimitField key={key} label={t(definition.label)} hint={t(definition.hint)} error={error}
                  name={name} minimum={bounds.minimum} value={draft[key]} restoreValue={finiteValues[key]} disabled={action.busy} onChange={value => {
                    if (value === String(UNLIMITED_VALUE) && draft[key] !== value) {
                      setFiniteValues(previous => ({...previous, [key]: draft[key].trim() && Number.isFinite(Number(draft[key]))
                        ? draft[key] : String(bounds.minimum)}));
                    }
                    setDraft(previous => ({...previous, [key]: value}));
                    clearFieldError(name);
                  }}/>;
              })}
            </div>
          </DetailSection>)}
          <p className={styles.note}>{t(current.note)}</p>
          <FormFeedback action={{...action, fieldErrors, clearFieldError}}
            onReload={async () => accept(await apiRequest<RuntimeSettings>(endpoint), keys)}/>
          <footer className={styles.actions}>
            <div className={styles.buttons}>
              <Button className={ui.primary} disabled={action.busy || !sectionDirty}>
                {action.busy ? t("正在保存…") : t("保存设置")}
              </Button>
              <Button className={ui.button} type="button" disabled={action.busy || !sectionDirty} onClick={event => {
                event.currentTarget.form?.reset();
                setDraft(previous => ({...previous, ...Object.fromEntries(keys.map(key => [key, String(saved.values[key])]))}));
                setClientErrors({});
                action.resetFeedback();
              }}>{t("取消修改")}</Button>
            </div>
            <span className={styles.changeNotice} aria-live="polite">
              {sectionDirty ? t("有未保存的修改") : ""}
            </span>
          </footer>
        </form>
      </AnimatedHeight>
    </div>
  </div>;
}
