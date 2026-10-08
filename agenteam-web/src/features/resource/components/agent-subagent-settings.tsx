"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Fieldset} from "@/components/ui/fieldset";
import {Checkbox} from "@/components/ui/checkbox";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";

import type {AgentConfig} from "../types/resource";
import type {FieldErrors} from "./resource-fields";
import {SubagentPicker} from "./subagent-picker";
import styles from "./agent-subagent-settings.module.css";
import ui from "@/components/ui/surface.module.css";

export function AgentSubagentSettings({enterpriseId, resourceId, allowed, value, onChange, errors, readOnly}: {
  enterpriseId: string;
  resourceId?: string;
  allowed: boolean;
  value: AgentConfig;
  onChange: (value: AgentConfig) => void;
  errors: FieldErrors;
  readOnly: boolean;
}) {
  const uiText = useT();
  return <Fieldset className={styles.settings} disabled={readOnly}>
    <legend>{uiText("子智能体")}</legend>
    <p className={styles.description}>{uiText("选择已有智能体协助处理任务，无需先雇佣。")}</p>
    <SubagentPicker enterpriseId={enterpriseId} resourceId={resourceId} selected={value.subagentVersionIds ?? []}
                    onChange={(subagentVersionIds) => onChange({...value, subagentVersionIds})} allowed={allowed}
                    readOnly={readOnly}/>
    <FieldErrorFeedback messages={errors["config.subagentVersionIds"]}/>
    <div className={styles.dynamic}>
      <label className={ui.check}><Checkbox name="config.dynamicSubagentEnabled"
                                            checked={value.dynamicSubagentEnabled ?? false}
                                            onCheckedChange={(checked) => onChange({
                                              ...value,
                                              dynamicSubagentEnabled: checked
                                            })}/>{uiText("允许智能体动态创建临时子智能体")}</label>
      <FieldErrorFeedback messages={errors["config.dynamicSubagentEnabled"]}/>
    </div>
  </Fieldset>;
}
