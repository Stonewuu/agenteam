"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";
import {Fieldset} from "@/components/ui/fieldset";
import {Textarea} from "@/components/ui/textarea";

import {Select} from "@/components/ui/select";


import {useEffect, useState} from "react";
import {Dialog} from "@/components/ui/dialog";
import {Toggle} from "@/components/ui/toggle";
import {IconInbox, IconPlayerPlay} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useAgentPreview} from "@/features/agent/hooks/use-agent-preview";
import {displayMessages} from "@/features/agent/lib/conversation-display";
import {ConversationBlocks, ConversationMarkdown} from "@/features/agent/components/conversation-message-blocks";
import {useConversationStepHistory} from "@/features/agent/hooks/use-conversation-step-history";
import {mergeExecutionSteps, messageRunSteps} from "@/features/agent/lib/conversation-steps";
import {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {FileUploadPicker} from "@/features/file/components/file-upload-picker";
import {FileDownloadButton} from "@/features/file/components/file-download-button";
import type {Run, RunStatus} from "@/features/agent/types/execution";
import type {AgentConfig, DraftWrite, ModelProfile, ResourceSummary} from "../types/resource";
import ui from "@/components/ui/surface.module.css";
import styles from "./agent-preview.module.css";
import {type ReasoningEffort, reasoningEffortLabels} from "@/features/modelprofile/lib/reasoning-effort";
import {workflowInDevelopment} from "@/features/workflow/components/workflow-development";

const states: Record<RunStatus, string> = {
  queued: "等待执行",
  running: "正在处理",
  waiting_approval: "等待确认",
  cancelling: "正在停止",
  completed: "已完成",
  failed: "未完成",
  cancelled: "已停止"
};

export function AgentPreview({enterpriseId, resource, draft, saving}: {
  enterpriseId: string;
  resource: ResourceSummary;
  draft: DraftWrite;
  saving: boolean
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const [compareEnabled, setCompare] = useState(false);
  const [text, setText] = useState("");
  const [firstModel, setFirstModel] = useState<string | null>(null);
  const [secondModel, setSecondModel] = useState("");
  const [efforts, setEfforts] = useState<Record<string, ReasoningEffort | null>>({});
  const workflow = (draft.config as AgentConfig).agentType === "workflow", compare = !workflow && compareEnabled;
  const profiles = useApiQuery<ModelProfile[]>(open && !workflow ? organizationPath(enterpriseId, "/model-profiles") : null);
  const first = useAgentPreview(enterpriseId, resource.id);
  const second = useAgentPreview(enterpriseId, resource.id);
  const modelId = firstModel ?? (draft.config as AgentConfig).modelProfileId ?? "";
  const busy = first.submitting || second.submitting;
  const active = first.active || second.active;
  const selected = [modelId, secondModel];
  const selectedEfforts = selected.map((id, index) => {
    const key = `${index}:${id}`;
    return key in efforts ? efforts[key] : id === (draft.config as AgentConfig).modelProfileId ? (draft.config as AgentConfig).reasoningEffort ?? null : null;
  });
  const valid = workflow || selected.slice(0, compare ? 2 : 1).every((id) => profiles.data?.some((model) => model.id === id && model.enabled));
  const hasResult = first.input !== null || second.input !== null;
  const files = useFileUploads(enterpriseId, "attachment", `preview:${resource.id}`);
  const attachmentsEnabled = (draft.config as AgentConfig).attachmentsEnabled;
  const sourcesReady = !files.items.length || (attachmentsEnabled && files.ready);
  const hasInput = Boolean(text.trim() || files.fileIds.length);

  function submit() {
    if (busy || active || !valid || !hasInput || !sourcesReady || workflow && workflowInDevelopment) {
      return;
    }
    if (!compare) {
      second.clear();
    }
    const requests = [first, ...(compare ? [second] : [])].map((preview, index) => {
      const model = profiles.data?.find((value) => value.id === selected[index]);
      return preview.start({
        draft: structuredClone(draft),
        modelProfileId: workflow ? null : model!.id,
        reasoningEffort: selectedEfforts[index],
        modelName: workflow ? resource.name : model!.name,
        text: text.trim(),
        revision: resource.revision,
        attachments: files.items.flatMap((item) => item.reference?.status === "ready" ? [item.reference] : [])
      });
    });
    void Promise.allSettled(requests);
  }

  return <>
    <Button className={ui.button} type="button" disabled={saving || workflow && workflowInDevelopment}
            onClick={() => setOpen(true)}>{workflow ? workflowInDevelopment ? uiText("工作流预览（开发中）") : uiText("预览流程员工") : uiText("预览与对比")}</Button>
    {open && !(workflow && workflowInDevelopment) &&
      <Dialog title={workflow ? uiText("预览流程员工") : uiText("预览与模型对比")} onClose={() => setOpen(false)}
              busy={busy} wide>
        <form className={ui.form} onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}>
          <Fieldset className={styles.inputs} disabled={busy || active || saving}>
            {!workflow && <div className={styles.compareToggle}>
              <div><strong>{uiText("对比预览")}</strong><p>{uiText("并排查看两次回复，可分别选择模型或停止。")}</p></div>
              <Toggle label={uiText("对比预览")} hideLabel checked={compare} disabled={busy || active || saving}
                      onChange={(value) => {
                        setCompare(value);
                        if (!value) {
                          second.clear();
                        }
                      }}/></div>}
            <label className={ui.field}><span>{uiText("测试内容")}</span><Textarea
              className={`${ui.input} ${styles.testInput}`} placeholder={uiText("输入一项工作，查看当前配置的表现…")}
              value={text} onChange={(event) => setText(event.target.value)} rows={3} maxLength={20000}
              required={!files.fileIds.length}/></label>
            {(attachmentsEnabled || files.items.length > 0) &&
              <FileUploadPicker uploads={files} purpose="attachment" allowNew={attachmentsEnabled}
                                disabled={busy || active || saving} label={uiText("添加测试附件")}/>}
            {!attachmentsEnabled && files.items.length > 0 &&
              <p className={ui.error}>{uiText("当前配置不接收附件，请移除文件或修改附件设置。")}</p>}
          </Fieldset>
          <div className={styles.actions}>
            <Button className={ui.primary}
                    disabled={busy || active || saving || !valid || !hasInput || !sourcesReady}><IconPlayerPlay
              size={16}/>{busy ? uiText("正在提交…") : hasResult ? uiText("再次预览") : uiText("开始预览")}</Button>
            {active &&
              <Button className={ui.button} type="button" disabled={first.stopping || second.stopping} onClick={() => {
                void Promise.allSettled([...(first.active ? [first.stop()] : []), ...(second.active ? [second.stop()] : [])]);
              }}>{uiText("全部停止")}</Button>}
            <p>{compare ? uiText("本次对比使用两次执行次数。") : uiText("本次预览使用一次执行次数。")}</p>
          </div>
        </form>
        <QueryState loading={!workflow && profiles.loading} error={workflow ? "" : profiles.error}
                    retry={profiles.retry} hasData={workflow || Boolean(profiles.data?.length)}
                    empty={uiText("暂无可选模型，请联系企业管理员。")}>
          <div className={compare ? styles.comparison : styles.single}>
            <section className={styles.result} aria-label={uiText("左侧预览")}>
              <header>{workflow ? <h3>{resource.name}</h3> : <ModelSelect
                label={compare ? uiText("左侧模型") : uiText("预览模型")} value={modelId} options={profiles.data ?? []}
                effort={selectedEfforts[0]}
                onEffort={(effort) => setEfforts((current) => ({...current, [`0:${modelId}`]: effort}))}
                disabled={busy || active || saving} onChange={(value) => {
                setFirstModel(value);
                first.clear();
              }}/>}</header>
              {first.input ? <PreviewResult preview={first} enterprise={enterpriseId}/> : <PreviewEmpty/>}
            </section>
            {compare && <section className={styles.result} aria-label={uiText("右侧预览")}>
              <header><ModelSelect
                label={uiText("右侧模型")} value={secondModel} options={profiles.data ?? []} effort={selectedEfforts[1]}
                onEffort={(effort) => setEfforts((current) => ({...current, [`1:${secondModel}`]: effort}))}
                disabled={busy || active || saving} onChange={(value) => {
                setSecondModel(value);
                second.clear();
              }}/></header>
              {second.input ? <PreviewResult preview={second} enterprise={enterpriseId}/> : <PreviewEmpty/>}
            </section>}
          </div>
        </QueryState>
        {active && <p className={ui.description}>{uiText("关闭后任务会继续，可再次打开查看。")}</p>}
      </Dialog>}
  </>;
}

function ModelSelect({label, value, options, onChange, disabled, effort, onEffort}: {
  label: string;
  value: string;
  options: ModelProfile[];
  onChange: (value: string) => void;
  disabled: boolean;
  effort: ReasoningEffort | null;
  onEffort: (effort: ReasoningEffort | null) => void
}) {
  const uiText = useT();
  const levels = options.find((model) => model.id === value)?.capabilities.reasoningEfforts ?? [];
  return <div className={styles.modelOptions}><label className={styles.modelField}><span>{uiText("模型")}</span><Select
    aria-label={label} className={styles.modelSelect} value={value} disabled={disabled}
    onChange={(event) => onChange(event.target.value)} required>
    <option value="">{uiText("请选择模型")}</option>
    {value && !options.some((model) => model.id === value) &&
      <option value={value} disabled>{uiText("原模型当前不可选")}</option>}
    {options.map((model) => <option value={model.id} key={model.id}
                                    disabled={!model.enabled}>{model.name}{model.enabled ? "" : uiText("（已停用）")}</option>)}
  </Select></label>{(levels.length > 0 || effort) &&
    <label className={styles.modelField}><span>{uiText("思考强度")}</span><Select
      aria-label={uiText("{0}思考强度", [label])} className={styles.modelSelect} value={effort ?? ""}
      disabled={disabled} onChange={(event) => onEffort(event.target.value as ReasoningEffort || null)}>
      <option value="">{uiText("默认")}</option>
      {effort && !levels.includes(effort) && <option value={effort} disabled>{uiText("原等级不可用")}</option>}
      {levels.map((level) => <option key={level}
                                     value={level}>{localizeCatalog(reasoningEffortLabels, uiText)[level]}</option>)}
    </Select></label>}</div>;
}

function PreviewEmpty() {
  const uiText = useT();
  return <div className={styles.empty}><span><IconInbox size={28}/></span><h3>{uiText("等待测试输入")}</h3>
    <p>{uiText("输入任务后，在这里查看结果。")}</p></div>;
}

function PreviewResult({preview, enterprise}: { preview: ReturnType<typeof useAgentPreview>; enterprise: string }) {
  const uiText = useT();
  const messages = displayMessages(preview.stream.state).filter((message) => message.role === "assistant");
  const history = useConversationStepHistory(enterprise, preview.stream.state?.snapshot.messages, preview.stream.historyVersion, preview.stream.historySequence);
  return <div className={styles.resultContent} aria-label={uiText("{0}的预览结果", [preview.input!.modelName])}>
    <header>
      <div><h3>{preview.input!.modelName}</h3>
        <span>{preview.submitting ? uiText("正在提交…") : preview.run ? localizeCatalog(states, uiText)[preview.run.status] : preview.accepted ? uiText("正在读取结果…") : uiText("尚未开始")}</span>
        {preview.run && <Elapsed run={preview.run}/>}</div>
      {preview.active &&
        <Button className={ui.button} type="button" disabled={preview.stopping || preview.run?.status === "cancelling"}
                onClick={() => void preview.stop()}>{preview.stopping ? uiText("正在停止…") : uiText("停止")}</Button>}
    </header>
    {preview.input!.text && <p className={styles.input}>{preview.input!.text}</p>}
    {preview.input!.attachments.map((file) => <FileDownloadButton key={file.id} enterpriseId={enterprise}
                                                                  file={file}/>)}
    {preview.error && <p className={ui.error} role="alert">{localizeUiMessage(preview.error ?? "", uiText)}</p>}
    {preview.stream.error &&
      <div className={ui.error} role="alert">{localizeUiMessage(preview.stream.error ?? "", uiText)}<Button
        className={ui.button} onClick={preview.stream.reload}>{uiText("重新加载")}</Button></div>}
    {!preview.accepted && !preview.submitting &&
      <Button className={ui.button} onClick={() => void preview.retry()}>{uiText("重试此预览")}</Button>}
    {messages.map((message) => {
      const saved = message.runId ? history.history.get(message.runId) : undefined;
      const blocks = mergeExecutionSteps(message.blocks, messageRunSteps(message, saved, preview.stream.state?.steps));
      return <div className={styles.output} key={message.id}>{blocks.length ?
        <ConversationBlocks blocks={blocks} enterprise={enterprise} runId={message.runId}
                            liveApprovals={preview.stream.state?.approvals} onChanged={preview.stream.reload}/> :
        <ConversationMarkdown content={message.content}/>}
        {saved?.error && <p className={ui.error} role="alert">{localizeUiMessage(saved.error ?? "", uiText)}<Button
          className="text-button" type="button" onClick={history.retry}>{uiText("重新读取执行过程")}</Button></p>}
      </div>;
    })}
    {preview.run?.errorMessage && <p className={ui.error}>{preview.run.errorMessage}</p>}
  </div>;
}

function Elapsed({run}: { run: Run }) {
  const uiText = useT();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!run.startedAt || run.finishedAt) {
      return;
    }
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [run.startedAt, run.finishedAt]);
  if (!run.startedAt) {
    return null;
  }
  const seconds = Math.max(0, ((run.finishedAt ? Date.parse(run.finishedAt) : now) - Date.parse(run.startedAt)) / 1000);
  return <span>{uiText("耗时 ")}{seconds.toFixed(1)}{uiText(" 秒")}</span>;
}
