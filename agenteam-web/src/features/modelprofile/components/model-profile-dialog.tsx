"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {type FormEvent, useId, useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/field";
import {Fieldset} from "@/components/ui/fieldset";
import {Checkbox} from "@/components/ui/checkbox";
import {Select} from "@/components/ui/select";
import {Dialog, DialogCancel} from "@/components/ui/dialog";
import {Toggle} from "@/components/ui/toggle";
import {DisclosureSummary} from "@/components/ui/disclosure";
import {Collapsible, CollapsibleContent} from "@/components/ui/shadcn/collapsible";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {RemoteModelPicker} from "./remote-model-picker";
import {TokenLengthInput} from "./token-length-input";
import {formatTokenLength, parseTokenLength, tokenLengthError} from "../lib/model-token-length";
import {reasoningEffortLabels, reasoningEfforts} from "../lib/reasoning-effort";
import type {ManagedModel, ModelCapabilities, ModelProvider, RemoteModel} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-management.module.css";
import formStyles from "./model-profile-form.module.css";

type CapabilityOptions = Pick<ModelCapabilities, "supportsTools" | "supportsTemperature" | "inputTypes" | "reasoningEfforts">;
const emptyOptions: CapabilityOptions = {
  supportsTools: false,
  supportsTemperature: false,
  inputTypes: ["text"],
  reasoningEfforts: []
};

export function ModelProfileDialog({enterpriseId, model, providers, preferredProvider, onClose, onSaved}: {
  enterpriseId: string; model: ManagedModel | null; providers: ModelProvider[]; preferredProvider: string;
  onClose: () => void; onSaved: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const initialProvider = model?.providerId ?? (preferredProvider || providers[0]?.id || "");
  const [name, setName] = useState(model?.name ?? "");
  const [providerId, setProviderId] = useState(initialProvider);
  const [modelName, setModelName] = useState(model?.modelName ?? "");
  const [enabled, setEnabled] = useState(model?.enabled ?? true);
  const [options, setOptions] = useState<CapabilityOptions>(model ? {
    supportsTools: model.capabilities.supportsTools, supportsTemperature: model.capabilities.supportsTemperature,
    inputTypes: model.capabilities.inputTypes, reasoningEfforts: model.capabilities.reasoningEfforts ?? [],
  } : emptyOptions);
  const [contextLength, setContextLength] = useState(formatTokenLength(model?.capabilities.maxContextTokens));
  const [outputLength, setOutputLength] = useState(formatTokenLength(model?.capabilities.maxOutputTokens));
  const [selected, setSelected] = useState<RemoteModel | null>(null);
  const [capabilitiesOpen, setCapabilitiesOpen] = useState(!model?.inUse);
  const [localErrors, setLocalErrors] = useState<Record<string, string[]>>({});
  const errors = {...action.fieldErrors, ...localErrors};
  const formId = useId();
  const inputErrorId = useId();
  const reasoningId = useId();
  const readOnly = action.busy || Boolean(model?.inUse);
  const draft = JSON.stringify({name, providerId, modelName, enabled, options, contextLength, outputLength});
  const [initialDraft] = useState(draft);
  const closing = useConfirmClose(draft !== initialDraft, action.busy, onClose);

  function clearError(field: string) {
    action.clearFieldError(field);
    setLocalErrors((current) => {
      if (!current[field]) {
        return current;
      }
      const next = {...current};
      delete next[field];
      return next;
    });
  }

  function resetCapabilities() {
    setOptions(emptyOptions);
    setContextLength("");
    setOutputLength("");
    setSelected(null);
    setLocalErrors({});
    action.resetFeedback();
  }

  function chooseRemote(remote: RemoteModel) {
    if (!name.trim() || name === modelName || name === selected?.name) {
      setName(Array.from(remote.name).slice(0, 80).join(""));
    }
    setModelName(remote.id);
    setSelected(remote);
    setContextLength(formatTokenLength(remote.maxContextTokens));
    setOutputLength(formatTokenLength(remote.maxOutputTokens));
    setOptions({
      supportsTools: remote.supportsTools ?? false, supportsTemperature: remote.supportsTemperature ?? false,
      inputTypes: remote.inputTypes ?? ["text"], reasoningEfforts: []
    });
    setLocalErrors({});
    action.resetFeedback();
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const invalid: Record<string, string[]> = {};
    if (!providerId) {
      invalid.providerId = [uiText("请选择模型提供方。")];
    }
    if (!modelName.trim()) {
      invalid.modelName = [uiText("请选择模型或填写模型标识。")];
    }
    if (!name.trim()) {
      invalid.name = [uiText("请填写显示名称。")];
    }
    const contextError = tokenLengthError(contextLength, uiText("上下文长度"), 10_000_000);
    const outputError = tokenLengthError(outputLength, uiText("最大输出长度"), 1_000_000);
    if (contextError) {
      invalid["capabilities.maxContextTokens"] = [contextError];
    }
    if (outputError) {
      invalid["capabilities.maxOutputTokens"] = [outputError];
    }
    const maxContextTokens = parseTokenLength(contextLength);
    const maxOutputTokens = parseTokenLength(outputLength);
    if (!contextError && !outputError && maxOutputTokens! > maxContextTokens!) {
      invalid["capabilities.maxOutputTokens"] = [uiText("最大输出长度不能超过上下文长度。")];
    }
    if (!options.inputTypes.length) {
      invalid["capabilities.inputTypes"] = [uiText("请至少选择一种输入类型。")];
      setCapabilitiesOpen(true);
    }
    setLocalErrors(invalid);
    if (Object.keys(invalid).length) {
      return;
    }
    void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, model ? `/models/${encodeURIComponent(model.id)}` : "/models"), {
        method: model ? "PUT" : "POST", revision: model?.revision,
        body: {
          providerId, name: name.trim(), modelName: modelName.trim(), enabled,
          capabilities: {...options, maxContextTokens, maxOutputTokens}
        },
      });
      closing.finish(onSaved);
    }, "");
  }

  const feedback = {...action, fieldErrors: errors, clearFieldError: clearError};
  return <><Dialog title={model ? uiText("编辑模型") : uiText("新增模型")} onClose={onClose}
                   dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={action.busy}
                   footer={<><DialogCancel className={ui.button}
                                           disabled={action.busy}>{uiText("取消")}</DialogCancel><Button form={formId}
                                                                                                         type="submit"
                                                                                                         className={ui.primary}
                                                                                                         disabled={action.busy}>{action.busy ? uiText("正在保存…") : uiText("保存模型")}</Button></>}>
    <div className={formStyles.formMotion}><AnimatedHeight preserveControlShadows>
      <form id={formId} className={`${ui.form} ${formStyles.formContent}`} noValidate onSubmit={submit}>
        {model?.inUse && <p
          className={ui.description}>{uiText("该模型已被使用，可以修改显示名称、启用状态及补充思考强度。其他能力请通过新增模型调整。")}</p>}
        <Fieldset className={styles.fields} disabled={readOnly}>
          <Field label={uiText("模型提供方")} required error={errors.providerId?.[0]}><Select className={ui.select}
                                                                                              name="providerId"
                                                                                              value={providerId}
                                                                                              onChange={(event) => {
                                                                                                if (name === modelName || name === selected?.name) {
                                                                                                  setName("");
                                                                                                }
                                                                                                setProviderId(event.target.value);
                                                                                                setModelName("");
                                                                                                resetCapabilities();
                                                                                              }}>
            <option value="" disabled>{uiText("请选择提供方")}</option>
            {providers.map((provider) => <option key={provider.id}
                                                 value={provider.id}>{provider.name}{provider.enabled ? "" : uiText("（已停用）")}</option>)}
          </Select></Field>
          <RemoteModelPicker key={providerId} enterpriseId={enterpriseId} providerId={providerId} value={modelName}
                             disabled={readOnly}
                             error={errors.modelName?.[0]}
                             onSelect={chooseRemote} onChange={(value) => {
            if (selected && value !== selected.id) {
              resetCapabilities();
            }
            setModelName(value);
            clearError("modelName");
          }}/>
        </Fieldset>
        <Field label={uiText("显示名称")} required error={errors.name?.[0]}><Input className={ui.input} name="name"
                                                                                   value={name} maxLength={80}
                                                                                   disabled={action.busy}
                                                                                   placeholder={uiText("用于识别这个模型")}
                                                                                   onChange={(event) => {
                                                                                     setName(event.target.value);
                                                                                     clearError("name");
                                                                                   }}/></Field>
        <section className={formStyles.lengthSection} aria-label={uiText("模型长度")}>
          <h3 className={formStyles.sectionHeading}>{uiText("模型长度")}</h3>
          <p className={ui.description}>{uiText("Token（模型计量文本的单位）：1K = 1,000，1M = 1,000,000。")}</p>
          {selected && (selected.maxContextTokens === null || selected.maxOutputTokens === null) &&
            <p className={ui.description}>{uiText("提供方未公布完整长度，请按模型说明补全。")}</p>}
          <Fieldset className={styles.fields} disabled={readOnly}>
            <div className={formStyles.lengthColumns}>
              <TokenLengthInput label={uiText("上下文长度")} name="capabilities.maxContextTokens" value={contextLength}
                                presets={[128_000, 256_000, 1_000_000, 2_000_000]}
                                error={errors["capabilities.maxContextTokens"]?.[0]} onChange={(value) => {
                setContextLength(value);
                clearError("capabilities.maxContextTokens");
                clearError("capabilities.maxOutputTokens");
              }}/>
              <TokenLengthInput label={uiText("最大输出长度")} name="capabilities.maxOutputTokens" value={outputLength}
                                presets={[16_000, 32_000, 64_000, 128_000]}
                                error={errors["capabilities.maxOutputTokens"]?.[0]} onChange={(value) => {
                setOutputLength(value);
                clearError("capabilities.maxOutputTokens");
              }}/>
            </div>
          </Fieldset>
        </section>
        <Collapsible className={styles.formSection} open={capabilitiesOpen} onOpenChange={setCapabilitiesOpen}>
          <DisclosureSummary>{uiText("模型能力")}</DisclosureSummary>
          <CollapsibleContent className="agenteam-disclosure-content" keepMounted inert={!capabilitiesOpen}>
            <div className={formStyles.capabilityContent}>
              <div className={formStyles.capabilitySettings}>
                <Fieldset className={formStyles.capabilityFields} disabled={readOnly}>
                  <div className={formStyles.inputSetting}>
                    <span id={`${inputErrorId}-label`}>{uiText("输入类型")}</span>
                    <Fieldset aria-labelledby={`${inputErrorId}-label`} className={formStyles.inputOptions}>
                      {(["text", "image"] as const).map((type) => <label className={formStyles.inputOption} key={type}>
                        <Checkbox name="capabilities.inputTypes" checked={options.inputTypes.includes(type)}
                                  aria-invalid={Boolean(errors["capabilities.inputTypes"])}
                                  aria-describedby={errors["capabilities.inputTypes"] ? inputErrorId : undefined}
                                  onCheckedChange={(checked) => {
                                    setOptions((current) => ({
                                      ...current,
                                      inputTypes: checked ? [...current.inputTypes.filter((item) => item !== type), type] : current.inputTypes.filter((item) => item !== type)
                                    }));
                                    clearError("capabilities.inputTypes");
                                  }}/>
                        <span>{type === "text" ? uiText("文字") : uiText("图片")}</span>
                      </label>)}
                    </Fieldset>
                  </div>
                  <div className={formStyles.capabilityError}><FieldErrorFeedback id={inputErrorId}
                                                                                  messages={errors["capabilities.inputTypes"]}/>
                  </div>
                  <div className={formStyles.switches}>
                    <Toggle label={uiText("支持工具调用")} checked={options.supportsTools}
                            onChange={(value) => setOptions((current) => ({...current, supportsTools: value}))}/>
                    <Toggle label={uiText("支持调整生成温度")} checked={options.supportsTemperature}
                            onChange={(value) => setOptions((current) => ({...current, supportsTemperature: value}))}/>
                  </div>
                </Fieldset>
                <Fieldset className={formStyles.reasoningSection} disabled={action.busy}
                          aria-labelledby={`${reasoningId}-label`}
                          aria-describedby={`${reasoningId}-hint${errors["capabilities.reasoningEfforts"] ? ` ${reasoningId}-error` : ""}`}>
                  <div className={formStyles.reasoningHeading}>
                    <h3 id={`${reasoningId}-label`}
                        className={formStyles.sectionHeading}>{uiText("支持的思考强度")}</h3>
                    <span>{uiText("可多选")}</span>
                  </div>
                  <p id={`${reasoningId}-hint`}
                     className={formStyles.capabilityHint}>{uiText("按提供方说明选择，不支持调整时留空。")}</p>
                  <div className={formStyles.reasoningOptions}>
                    {reasoningEfforts.map((effort) => <label className={formStyles.reasoningOption} key={effort}>
                      <Checkbox name="capabilities.reasoningEfforts"
                                checked={options.reasoningEfforts?.includes(effort) ?? false}
                                disabled={Boolean(model?.inUse && model.capabilities.reasoningEfforts?.includes(effort))}
                                aria-invalid={Boolean(errors["capabilities.reasoningEfforts"])}
                                aria-describedby={errors["capabilities.reasoningEfforts"] ? `${reasoningId}-error` : undefined}
                                onCheckedChange={(checked) => {
                                  setOptions((current) => ({
                                    ...current,
                                    reasoningEfforts: reasoningEfforts.filter((value) => value === effort ? checked : current.reasoningEfforts?.includes(value))
                                  }));
                                  clearError("capabilities.reasoningEfforts");
                                }}/>
                      <span>{localizeCatalog(reasoningEffortLabels, uiText)[effort]}</span>
                    </label>)}
                  </div>
                  <FieldErrorFeedback id={`${reasoningId}-error`} messages={errors["capabilities.reasoningEfforts"]}/>
                </Fieldset>
              </div>
            </div>
          </CollapsibleContent>
        </Collapsible>
        <div className={formStyles.enabled}><Toggle label={uiText("启用此模型")} checked={enabled}
                                                    disabled={action.busy} onChange={setEnabled}/></div>
        <MutationFeedback action={feedback}/>
      </form>
    </AnimatedHeight></div>
  </Dialog>{closing.confirmation}</>;
}
