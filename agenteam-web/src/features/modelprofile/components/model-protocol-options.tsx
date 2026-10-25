"use client";

import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {Checkbox} from "@/components/ui/checkbox";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {useT} from "@/lib/i18n/locale-provider";
import type {ReasoningEffort} from "../lib/reasoning-effort";
import {reasoningEffortLabels} from "../lib/reasoning-effort";
import type {ModelProtocol, ModelProtocolOptions} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-profile-form.module.css";

type Props = {
  protocol: ModelProtocol;
  value: ModelProtocolOptions;
  onChange: (value: ModelProtocolOptions) => void;
  efforts: ReasoningEffort[];
  disabled: boolean;
  errors: Record<string, string[]>;
};

export function ModelProtocolFields({protocol, value, onChange, efforts, disabled, errors}: Props) {
  const t = useT();
  const native = protocol === "anthropic" || protocol === "gemini";
  const error = (name: string) => errors[`capabilities.protocolOptions.${name}`]?.join(" ");
  const levels = protocol === "anthropic" ? ["low", "medium", "high", "max"] as const : ["minimal", "low", "medium", "high"] as const;
  const labels = {minimal: t("极低"), low: t("低"), medium: t("中"), high: t("高"), max: t("最高")};
  function budget(effort: ReasoningEffort, input: string) {
    const values = {...value.thinkingBudgets};
    if (input === "") {
      delete values[effort];
    } else {
      values[effort] = Number(input);
    }
    onChange({...value, thinkingBudgets: values});
  }
  return <section className={styles.protocolSection} aria-label={t("协议选项")}>
    <h3 className={styles.sectionHeading}>{t("协议选项")}</h3>
    <AnimatedHeight preserveControlShadows>
      <div className={ui.form}>
        {native && efforts.length > 0 && <>
          <Field label={t("思考方式")} error={error("reasoningMode")}><Select name="capabilities.protocolOptions.reasoningMode"
            value={value.reasoningMode} disabled={disabled}
            onChange={(event) => onChange({...value, reasoningMode: event.target.value as ModelProtocolOptions["reasoningMode"]})}>
            <option value="budget">{t("按 Token 预算")}</option>
            <option value={protocol === "anthropic" ? "adaptive" : "level"}>{protocol === "anthropic" ? t("自适应思考") : t("按思考强度设置")}</option>
          </Select></Field>
          <div className={styles.lengthColumns}>{efforts.map((effort) => value.reasoningMode === "budget" ?
            <Field key={effort} label={t("{0}：思考预算", [t(reasoningEffortLabels[effort])])}
              error={error(`thinkingBudgets.${effort}`) ?? error("thinkingBudgets")}>
              <Input type="number" min={0} max={1000000} step={1} name={`capabilities.protocolOptions.thinkingBudgets.${effort}`}
                value={value.thinkingBudgets[effort] ?? ""} disabled={disabled}
                onChange={(event) => budget(effort, event.target.value)}/>
            </Field> : effort !== "none" && <Field key={effort} label={t("{0}：原生等级", [t(reasoningEffortLabels[effort])])}
              error={error(`thinkingLevels.${effort}`) ?? error("thinkingLevels")}>
              <Select name={`capabilities.protocolOptions.thinkingLevels.${effort}`} value={value.thinkingLevels[effort] ?? ""}
                disabled={disabled} onChange={(event) => onChange({...value,
                  thinkingLevels: {...value.thinkingLevels, [effort]: event.target.value}})}>
                <option value="">{t("请选择")}</option>
                {levels.map((level) => <option key={level} value={level}>{labels[level]}</option>)}
              </Select>
            </Field>)}</div>
        </>}
        {protocol === "anthropic" && <Field label={t("缓存创建有效期")} error={error("cacheTtlSeconds")}>
          <Select name="capabilities.protocolOptions.cacheTtlSeconds" value={value.cacheTtlSeconds} disabled={disabled}
            onChange={(event) => onChange({...value, cacheTtlSeconds: Number(event.target.value)})}>
            <option value={0}>{t("不开启自动缓存")}</option><option value={300}>{t("5 分钟")}</option><option value={3600}>{t("1 小时")}</option>
          </Select>
        </Field>}
        {protocol !== "gemini" && <Field label={t("服务档位")} error={error("serviceTier")}>
          <Select name="capabilities.protocolOptions.serviceTier" value={value.serviceTier ?? ""} disabled={disabled}
            onChange={(event) => onChange({...value, serviceTier: event.target.value || null})}>
            <option value="">{t("使用提供方默认值")}</option><option value="auto">{t("自动选择")}</option>
            {protocol === "anthropic" ? <option value="standard_only">{t("仅标准服务")}</option> : <>
              <option value="default">{t("标准服务")}</option><option value="flex">Flex</option><option value="priority">{t("优先服务")}</option>
            </>}
          </Select>
        </Field>}
        {protocol === "openai" && <Field label={t("用量格式")} error={error("usageFormat")}>
          <Select name="capabilities.protocolOptions.usageFormat" value={value.usageFormat} disabled={disabled}
            onChange={(event) => onChange({...value, usageFormat: event.target.value as ModelProtocolOptions["usageFormat"]})}>
            <option value="standard">OpenAI</option><option value="deepseek">DeepSeek</option>
          </Select>
        </Field>}
        <Field label={t("不适用的计量项")} error={error("notApplicableUsage")}
          hint={t("仅选择模型确实不适用的项目；未返回用量不代表不适用。") }>
          <div className={styles.reasoningOptions}>{([
            ["cache_read", t("缓存读取")], ["cache_write", t("缓存创建")], ["reasoning", t("思考")],
            ...(protocol === "gemini" ? [["tool_use_prompt", t("工具输入")]] : []),
          ] as [ModelProtocolOptions["notApplicableUsage"][number], string][]).filter(([key]) => !(key === "cache_write" && protocol === "gemini"))
            .map(([key, label]) => <label key={key} className={styles.reasoningOption}><Checkbox checked={value.notApplicableUsage.includes(key)} disabled={disabled}
              onCheckedChange={(checked) => onChange({...value, notApplicableUsage: checked ? [...value.notApplicableUsage, key]
                : value.notApplicableUsage.filter((item) => item !== key)})}/><span>{label}</span></label>)}</div>
        </Field>
      </div>
    </AnimatedHeight>
  </section>;
}
