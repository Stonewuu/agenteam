"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {createPortal} from "react-dom";
import {Button} from "@/components/ui/button";
import {Select} from "@/components/ui/select";
import {Textarea} from "@/components/ui/textarea";
import {IconSparkles, IconX} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {useEnterpriseIdentity} from "@/features/auth/components/enterprise-gate";
import ConversationWorkspace from "@/features/agent/components/conversation-workspace";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {ModelProfile} from "@/features/resource/types/resource";
import type {RunAccepted} from "@/features/agent/types/execution";
import {useT} from "@/lib/i18n/locale-provider";
import {ErrorFeedback} from "@/components/ui/error-feedback";
import ui from "@/components/ui/surface.module.css";
import panels from "./workflow-panels.module.css";

export function WorkflowAssistantButton({enterprise, permissions, resourceId, disabled = false, open: controlledOpen, onOpenChange, container, onChanged}: {
  enterprise: string;
  permissions: string[];
  resourceId?: string;
  disabled?: boolean;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  container?: HTMLElement | null;
  onChanged?: () => void;
}) {
  const text = useT();
  const [localOpen, setLocalOpen] = useState(false);
  const [mounted, setMounted] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const open = controlledOpen ?? localOpen;
  const setOpen = (next: boolean) => {
    if (next) {
      setMounted(true);
    }
    setLocalOpen(next);
    onOpenChange?.(next);
    if (!next) {
      requestAnimationFrame(() => trigger.current?.focus({preventScroll: true}));
    }
  };
  const allowed = ["conversation.view", "agent.create", "agent.publish", "agent.hire", "agent.run", "plugin.invoke"].every((code) => permissions.includes(code));
  const panel = (mounted || open) && <WorkflowAssistantPanel enterprise={enterprise} resourceId={resourceId} open={open}
    floating={Boolean(container)} onClose={() => setOpen(false)} onChanged={onChanged}/>;
  return <><Button ref={trigger} className={ui.button} type="button" disabled={!allowed || disabled} aria-expanded={open}
    title={!allowed ? text("需要查看对话、创建和使用智能体及插件的权限。") : disabled ? text("保存草稿后请助手修改。") : undefined}
    onClick={() => setOpen(!open)}><IconSparkles size={16}/>{resourceId ? text("请助手修改") : text("对话创建")}</Button>
    {container ? createPortal(panel, container) : panel}
  </>;
}

function WorkflowAssistantPanel({enterprise, resourceId, open, floating, onClose, onChanged}: {
  enterprise: string;
  resourceId?: string;
  open: boolean;
  floating: boolean;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const text = useT();
  const identity = useEnterpriseIdentity();
  const panel = useRef<HTMLElement>(null);
  const lastFocus = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (!open) {
      return;
    }
    const frame = requestAnimationFrame(() => {
      const target = lastFocus.current?.isConnected ? lastFocus.current : panel.current?.querySelector<HTMLElement>("textarea,button");
      target?.focus({preventScroll: true});
    });
    return () => cancelAnimationFrame(frame);
  }, [open]);
  const [location, setLocation] = useState<{pathname: string; search: string} | null>(null);
  const navigate = useCallback((path: string) => {
    const split = path.indexOf("?");
    setLocation({pathname: split < 0 ? path : path.slice(0, split), search: split < 0 ? "" : path.slice(split + 1)});
  }, []);
  const closeButton = <Button type="button" className="icon-button" aria-label={text("收起工作流助手")} onClick={onClose}><IconX size={18}/></Button>;
  return <aside ref={panel} className={`${panels.floating} ${panels.assistant} ${floating ? "" : panels.fixed}`}
    data-open={open} aria-label={text("工作流助手")} aria-hidden={!open} inert={!open}
    onFocusCapture={(event) => {
      lastFocus.current = event.target as HTMLElement;
    }} onKeyDown={(event) => {
      if (event.key === "Escape" && !event.defaultPrevented) {
        event.stopPropagation();
        onClose();
      }
    }}>
    {location && identity ? <ConversationWorkspace user={identity.user} context={identity.context}
      location={{...location, navigate, active: open}} headerActions={closeButton} onRunFinished={onChanged}/>
      : <><header className={panels.header}><span><IconSparkles size={19}/>{text("工作流助手")}</span>{closeButton}</header>
        <WorkflowAssistantStart enterprise={enterprise} resourceId={resourceId} onStarted={(result) => {
          navigate(`/conversations/${encodeURIComponent(result.conversationId)}`);
        }}/></>}
  </aside>;
}

function WorkflowAssistantStart({enterprise, resourceId, onStarted}: {
  enterprise: string;
  resourceId?: string;
  onStarted: (result: RunAccepted) => void;
}) {
  const text = useT(), action = useFormAction();
  const profiles = useApiQuery<ModelProfile[]>(organizationPath(enterprise, "/model-profiles"));
  const [model, setModel] = useState(""), [message, setMessage] = useState("");
  const available = profiles.data?.filter((item) => item.enabled && item.capabilities.supportsTools && item.capabilities.inputTypes.includes("text")) ?? [];
  const selected = model || available[0]?.id || "";
  return <form className={panels.setup} onSubmit={(event) => {
    event.preventDefault();
    if (!selected || !message.trim() || action.busy) {
      return;
    }
    void action.execute(async () => {
      const result = await action.mutation.run<RunAccepted>(organizationPath(enterprise, "/agent-templates/workflow_builder/conversations"), {
        method: "POST", body: {modelProfileId: selected, message: message.trim(), targetResourceId: resourceId ?? null}
      });
      onStarted(result);
    }, "");
  }}>
    <div className={panels.setupContent}>
      <p className={ui.description}>{text("说清目标、需要处理的内容和预期结果，助手会帮你创建或调整工作流草稿。")}</p>
      <QueryState {...profiles} hasData={Boolean(profiles.data)} empty={text("暂无可用模型。")}>{profiles.data && <div className={ui.form}>
        <label className={ui.field}><span>{text("使用模型")}</span><Select value={selected} disabled={action.busy} onChange={(event) => setModel(event.target.value)}>
          {!available.length && <option value="">{text("请先配置支持工具调用的模型")}</option>}
          {available.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </Select></label>
        <label className={ui.field}><span>{resourceId ? text("希望怎样调整") : text("工作目标")}</span>
          <Textarea value={message} rows={5} maxLength={18000} disabled={action.busy}
            placeholder={text("例如：逐条分析客户反馈，判断是否需要人工确认，再整理为处理建议。")}
            onChange={(event) => setMessage(event.target.value)}/></label>
      </div>}</QueryState>
      <ErrorFeedback error={action.error}/>
    </div>
    <Button type="submit" className={ui.primary} disabled={action.busy || !selected || !message.trim()}><IconSparkles size={16}/>
      {action.busy ? text("正在准备对话…") : text("开始对话")}</Button>
  </form>;
}
