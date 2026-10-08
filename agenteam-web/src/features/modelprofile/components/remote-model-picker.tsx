"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useId, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {SearchInput} from "@/components/ui/search-input";
import {SelectionAction, SelectionSurface} from "@/components/ui/selection-surface";
import {IconChevronDown, IconRefresh} from "@/components/ui/icons";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {formatTokenLength} from "../lib/model-token-length";
import type {RemoteModel} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-profile-form.module.css";

export function RemoteModelPicker({enterpriseId, providerId, value, disabled, error, onChange, onSelect}: {
  enterpriseId: string; providerId: string; value: string; disabled: boolean;
  error?: string;
  onChange: (value: string) => void; onSelect: (model: RemoteModel) => void;
}) {
  const uiText = useT();
  const remote = useApiQuery<RemoteModel[]>(!disabled && providerId
    ? organizationPath(enterpriseId, `/model-providers/${encodeURIComponent(providerId)}/remote-models`) : null);
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const inputId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const visible = remote.data?.filter((model) => `${model.id} ${model.name}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())) ?? [];
  return <div className={styles.remoteField}>
    <label className={styles.fieldLabel} htmlFor={inputId}>{uiText("模型标识")}<span
      className="required"> *</span></label>
    <div className={styles.modelInput}>
      <Input id={inputId} className={`${ui.input} ${styles.modelIdentifier}`} name="modelName" value={value}
             disabled={disabled}
             aria-invalid={Boolean(error)} aria-describedby={error ? `${inputId}-error` : undefined} maxLength={128}
             placeholder={uiText("选择模型或手动填写标识")}
             onChange={(event) => onChange(event.target.value)}/>
      {!disabled &&
        <Button ref={trigger} type="button" className={styles.modelPickerTrigger} title={uiText("选择远程模型")}
                aria-label={uiText("选择远程模型")} aria-haspopup="dialog" aria-expanded={open}
                disabled={!providerId} onClick={() => {
          setSearch("");
          setOpen(true);
        }}><IconChevronDown size={16}/></Button>}
    </div>
    <FieldErrorFeedback id={`${inputId}-error`} messages={error ? [error] : []}/>
    {!disabled && <AnimatedHeight>
      <div className={styles.remoteStatus}>
      <span
        role="status">{remote.loading ? uiText("正在获取远程模型…") : remote.error ? uiText("暂时无法获取模型列表，仍可手动填写。")
        : remote.data?.length ? uiText("可从 {0} 个远程模型中选择", [remote.data.length]) : uiText("提供方未返回模型，可手动填写。")}</span>
        <Button type="button" aria-label={uiText("刷新远程模型")} disabled={remote.loading || !providerId}
                onClick={remote.retry}><IconRefresh size={14}/></Button>
      </div>
    </AnimatedHeight>}
    {open && !disabled && <SelectionSurface title={uiText("选择远程模型")} anchor={trigger} returnFocus={trigger}
                                            onClose={() => setOpen(false)}>
      <div className="selection-search"><SearchInput aria-label={uiText("搜索远程模型")}
                                                     placeholder={uiText("搜索模型名称或标识…")} value={search}
                                                     onChange={(event) => setSearch(event.target.value)}/></div>
      <div className="selection-results" aria-busy={remote.loading}>
        {remote.loading && !remote.data &&
          <p className={styles.listMessage} role="status">{uiText("正在获取远程模型…")}</p>}
        {remote.error && <FieldErrorFeedback messages={[remote.error]}/>}
        {!remote.loading && !remote.error && !visible.length &&
          <p className={styles.listMessage}>{search ? uiText("没有找到匹配的模型。") : uiText("提供方未返回模型。")}</p>}
        <div data-selection-list>{visible.map((model) => <SelectionAction key={model.id} data-selection-option
                                                                          aria-pressed={value === model.id}
                                                                          onAction={(close) => {
                                                                            onSelect(model);
                                                                            close();
                                                                          }}>
          <span className={styles.remoteOption}><strong>{model.name}</strong>{model.name !== model.id &&
            <small>{model.id}</small>}
            {model.maxContextTokens !== null &&
              <small>{uiText("上下文 ")}{formatTokenLength(model.maxContextTokens)}</small>}</span>
        </SelectionAction>)}</div>
      </div>
      <div className="selection-footer"><span className="selection-hint">{uiText("也可返回表单手动填写")}</span><Button
        type="button" className={ui.button} disabled={remote.loading} onClick={remote.retry}>
        <IconRefresh size={14}/>{remote.loading ? uiText("正在获取…") : uiText("重新获取")}</Button></div>
    </SelectionSurface>}
  </div>;
}
