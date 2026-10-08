"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";
import {Fieldset} from "@/components/ui/fieldset";

import {useState} from "react";
import {Dialog} from "@/components/ui/dialog";
import {displayMessages} from "@/features/agent/lib/conversation-display";
import {ConversationBlocks} from "@/features/agent/components/conversation-message-blocks";
import type {BlockStatus, RunStatus} from "@/features/agent/types/execution";
import type {ResourceSummary, WorkflowConfig} from "@/features/resource/types/resource";
import {useWorkflowPreview} from "../hooks/use-workflow-preview";
import {useWorkflowSteps} from "../hooks/use-workflow-steps";
import {previewExample} from "../lib/workflow-preview-input";
import {record} from "../lib/workflow-graph";
import {JsonField} from "./workflow-value-fields";
import {WorkflowGraphCanvas} from "./workflow-graph-canvas";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

const states: Record<RunStatus, string> = {
  queued: "等待执行",
  running: "正在处理",
  waiting_approval: "等待确认",
  cancelling: "正在停止",
  completed: "已完成",
  failed: "未完成",
  cancelled: "已停止"
};

export function WorkflowPreview({enterprise, resource, draft, disabled}: {
  enterprise: string;
  resource: ResourceSummary;
  draft: WorkflowConfig;
  disabled: boolean
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false), [input, setInput] = useState(() => record(previewExample(record(draft.nodes.find((node) => node.type === "start")?.config.inputSchema))));
  const [selected, setSelected] = useState("");
  const preview = useWorkflowPreview(enterprise, resource.id),
    steps = useWorkflowSteps(enterprise, preview.accepted?.runId ?? null, preview.active);
  const statuses: Record<string, BlockStatus> = {};
  for (const step of steps.steps) {
    if (step.workflow?.resourceId === resource.id && step.workflow.nodeId) {
      statuses[step.workflow.nodeId] = step.status;
    }
  }
  const messages = displayMessages(preview.stream.state?.snapshot.conversation.id === preview.accepted?.conversationId ? preview.stream.state : null).filter((message) => message.role === "assistant");
  const current = steps.steps.find((step) => step.workflow?.resourceId === resource.id && step.workflow.nodeId === selected);
  return <>
    <Button type="button" className={ui.button} disabled={disabled} onClick={(event) => {
      if (!event.currentTarget.form || event.currentTarget.form.reportValidity()) {
        setOpen(true);
      }
    }}>{uiText("测试工作流")}</Button>
    {open &&
      <Dialog title={uiText("测试 · {0}", [resource.name])} onClose={() => setOpen(false)} busy={preview.submitting}
              wide>
        <div className={styles.preview}>
          <form className={ui.form} onSubmit={(event) => {
            event.preventDefault();
            void preview.start({
              draft: structuredClone(draft),
              input: structuredClone(input),
              revision: resource.revision
            });
          }}>
            <Fieldset className={styles.panel} disabled={preview.active || preview.submitting || disabled}><JsonField
              label={uiText("测试输入（JSON 对象）")} value={input} object
              onChange={(value) => setInput(value as Record<string, unknown>)} rows={5}/></Fieldset>
            <div className={styles.previewStatus}><Button className={ui.primary}
                                                          disabled={preview.active || preview.submitting || disabled}>{preview.submitting ? uiText("正在提交…") : preview.accepted ? uiText("再次测试") : uiText("开始测试")}</Button>
              {preview.active && <Button className={ui.button} type="button"
                                         disabled={preview.stopping || preview.run?.status === "cancelling"}
                                         onClick={() => void preview.stop()}>{preview.stopping ? uiText("正在停止…") : uiText("停止")}</Button>}
              <p className={ui.description}>{uiText("使用当前编辑内容，每次测试使用一次执行次数。")}</p></div>
          </form>
          {preview.error && <p className={ui.error} role="alert">{localizeUiMessage(preview.error ?? "", uiText)}</p>}
          {preview.accepted && <>
            <div className={styles.previewStatus}>
              <strong>{preview.run ? localizeCatalog(states, uiText)[preview.run.status] : uiText("正在读取结果…")}</strong>{preview.run?.hasStepErrors &&
              <span className={ui.error}>{uiText("部分步骤未成功")}</span>}</div>
            <WorkflowGraphCanvas graph={preview.request!.draft} selected={selected} onSelect={setSelected}
                                 nodeStatuses={statuses}/>
            {current?.publicSummary && <p className={ui.description}>{current.title}：{current.publicSummary}</p>}
            {steps.error &&
              <p className={ui.error} role="alert">{localizeUiMessage(steps.error ?? "", uiText)}<Button type="button"
                                                                                                         className={ui.button}
                                                                                                         onClick={steps.retry}>{uiText("重新读取步骤")}</Button>
              </p>}
            {preview.stream.error &&
              <p className={ui.error} role="alert">{localizeUiMessage(preview.stream.error ?? "", uiText)}<Button
                type="button" className={ui.button} onClick={preview.stream.reload}>{uiText("重新读取结果")}</Button>
              </p>}
            {messages.map((message) => <div className={styles.previewOutput} key={message.id}><ConversationBlocks
              blocks={message.blocks} enterprise={enterprise} runId={message.runId} onChanged={preview.stream.reload}/>
            </div>)}
            {preview.run?.errorMessage && <p className={ui.error} role="alert">{preview.run.errorMessage}</p>}
            {preview.active && <p className={ui.description}>{uiText("关闭窗口后测试会继续，可再次打开查看。")}</p>}
          </>}
        </div>
      </Dialog>}
  </>;
}
