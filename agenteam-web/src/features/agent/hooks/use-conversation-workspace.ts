"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {usePathname, useSearchParams} from "next/navigation";
import {ApiMutation, errorMessage} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {Employee} from "@/features/employee/types/employee";
import {conversationPath, loadRun, runPath, textInput} from "../api/conversation-api";
import type {Conversation, RunAccepted} from "../types/execution";
import {displayMessages} from "../lib/conversation-display";
import {useConversationList} from "./use-conversation-list";
import {useConversationStream} from "./use-conversation-stream";
import {useConversationResultViewed} from "./use-conversation-result-viewed";
import {useConversationScroll} from "./use-conversation-scroll";
import {useConversationStepHistory} from "./use-conversation-step-history";
import {useConversationSkills} from "@/features/skill/hooks/use-conversation-skills";
import type {SkillOption} from "@/features/skill/types/skill";
import type {DocumentOption} from "@/features/knowledge/types/knowledge";
import {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import type {HomeSummary} from "@/features/workspace/types/workspace";
import {useGuardedNavigation} from "@/features/workspace/components/navigation-guard";
import {useConversationApprovalPolicy} from "./use-conversation-approval-policy";
import {useConversationModel} from "./use-conversation-model";
import {useComposerHandoff} from "../components/composer-handoff-context";
import {useConversationProject} from "@/features/project/hooks/use-conversation-project";

export function useConversationWorkspace(userId: string, enterpriseId: string, canRun: boolean, canUseSkills: boolean, canUseKnowledge: boolean, sharedList?: ReturnType<typeof useConversationList>, canManage = false) {
  const uiText = useT();
  const pathname = usePathname();
  const params = useSearchParams();
  const guarded = useGuardedNavigation();
  const parts = pathname.split("/");
  const conversationId = parts[3] === "conversations" && parts[4] ? decodeURIComponent(parts[4]) : null;
  const handoff = useComposerHandoff();
  const arrival = handoff?.value?.conversationId === conversationId ? handoff.value : null;
  const recent = useApiQuery<HomeSummary>(!conversationId && !params.get("agent") && canRun ? organizationPath(enterpriseId, "/home") : null);
  const selectedAgent = conversationId ? null : params.get("agent") ?? recent.data?.employees[0]?.agentId ?? null;
  const localList = useConversationList(enterpriseId, !sharedList);
  const list = sharedList ?? localList;
  const stream = useConversationStream(enterpriseId, conversationId);
  useConversationResultViewed(enterpriseId, conversationId, stream.state?.latestRun,
    Boolean(stream.state && !stream.state.replaying && !stream.loading && !stream.error));
  const steps = useConversationStepHistory(enterpriseId, stream.state?.snapshot.messages, stream.historyVersion, stream.historySequence);
  const detail = stream.state?.snapshot.conversation;
  const skills = useConversationSkills(userId, enterpriseId, detail?.agentId ?? selectedAgent, conversationId, params.get("skill"), canUseSkills);
  const [skillPickerOpen, setSkillPickerOpen] = useState(false);
  const employeeId = detail ? detail.agentId : selectedAgent ?? arrival?.employee.agentId ?? null;
  const employee = useApiQuery<Employee>(employeeId ? organizationPath(enterpriseId, `/employees/${encodeURIComponent(employeeId)}`) : null);
  const [chosenEmployee, setChosenEmployee] = useState<Employee | null>(null);
  const employeeLoading = !conversationId && (recent.loading || employee.loading);
  const displayEmployee = employee.data ?? (chosenEmployee?.agentId === employeeId ? chosenEmployee
    : recent.data?.employees.find((value) => value.agentId === employeeId) ?? (arrival?.employee.agentId === employeeId ? arrival.employee : null));
  const employeeAppearance = detail ? {
    icon: detail.agentIcon ?? "",
    color: detail.agentColor ?? "purple"
  } : displayEmployee;
  const finishHandoff = handoff?.finish;
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [actionError, setActionError] = useState("");
  const pending = useRef(false);
  const mutation = useRef(new ApiMutation());
  const stopMutation = useRef(new ApiMutation());
  const retryMutation = useRef(new ApiMutation());
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const draftKey = `${userId}:${enterpriseId}:${conversationId ?? `new:${selectedAgent ?? ""}`}`;
  const files = useFileUploads(enterpriseId, "attachment", draftKey);
  const [documents, setDocuments] = useState<Record<string, DocumentOption[]>>({});
  const [documentPickerOpen, setDocumentPickerOpen] = useState(false);
  const selectedDocuments = documents[draftKey] ?? [];
  const attachmentsEnabled = conversationId ? Boolean(stream.state?.snapshot.attachmentsEnabled) : Boolean(employee.data?.attachmentsEnabled);
  const sourceError = files.items.length && !attachmentsEnabled ? uiText("当前员工不接收附件，请移除所选文件。")
    : selectedDocuments.length && !canUseKnowledge ? uiText("当前无法引用资料，请移除所选资料。") : "";
  const sourcesReady = !sourceError && (!files.items.length || files.ready);
  const draft = drafts[draftKey] ?? "";
  const {
    conversationRef,
    shouldStickToBottomRef,
    scrollToBottom,
    preserveHistoryPosition,
    awayFromBottom,
    hasUnseenContent
  } = useConversationScroll(draftKey, stream.state?.snapshot.messages, stream.state?.steps);
  const currentRoute = useRef(pathname + "?" + params.toString());
  const messages = useMemo(() => displayMessages(stream.state), [stream.state]);
  const base = `/enterprises/${encodeURIComponent(enterpriseId)}`;

  useEffect(() => {
    currentRoute.current = pathname + "?" + params.toString();
  }, [pathname, params]);

  const refreshList = list.refresh;
  const reloadConversation = stream.reload;
  const latestRunId = stream.state?.latestRun?.id;
  const latestRunStatus = stream.state?.latestRun?.status;
  const changed = useCallback(() => {
    refreshList();
    reloadConversation();
  }, [refreshList, reloadConversation]);
  const approvalPolicy = useConversationApprovalPolicy({
    userId,
    enterprise: enterpriseId,
    draftKey,
    conversationId,
    conversation: detail,
    enabled: canRun && (!conversationId || canManage),
    busy: submitting || Boolean(stream.state?.snapshot.activeRun),
    onChanged: changed,
    initialPolicy: arrival?.approvalPolicy
  });
  const model = useConversationModel({
    enterprise: enterpriseId,
    agentId: detail?.agentId ?? selectedAgent,
    draftKey,
    conversation: detail,
    enabled: canRun && (!conversationId || Boolean(detail)),
    busy: submitting || approvalPolicy.saving || Boolean(stream.state?.snapshot.activeRun),
    onChanged: changed,
    initialOptions: arrival?.modelOptions
  });
  const project = useConversationProject({
    enterprise: enterpriseId,
    draftKey,
    conversationId,
    conversation: detail,
    enabled: canRun,
    busy: submitting || approvalPolicy.saving || model.saving || Boolean(stream.state?.snapshot.activeRun),
    onChanged: changed,
    initialProject: arrival?.project
  });
  useEffect(() => {
    if (arrival && (stream.error || detail && employee.data && (model.loaded || !canRun))) {
      finishHandoff?.(arrival.conversationId);
    }
  }, [arrival, detail, employee.data, finishHandoff, stream.error, model.loaded, canRun]);
  useEffect(() => {
    const refresh = (event: Event) => {
      const detail = (event as CustomEvent<{ enterpriseId: string; conversationId: string }>).detail;
      if (detail?.enterpriseId === enterpriseId && detail.conversationId === conversationId) {
        reloadConversation();
      }
    };
    window.addEventListener("agenteam:conversation-changed", refresh);
    return () => window.removeEventListener("agenteam:conversation-changed", refresh);
  }, [enterpriseId, conversationId, reloadConversation]);
  useEffect(() => {
    if (latestRunId && latestRunStatus && ["completed", "failed", "cancelled"].includes(latestRunStatus)) {
      refreshList();
    }
  }, [latestRunId, latestRunStatus, refreshList]);

  function setDraft(text: string) {
    setDrafts((current) => ({...current, [draftKey]: text}));
  }

  function navigate(path: string, replace = false) {
    shouldStickToBottomRef.current = true;
    setSidebarOpen(false);
    setActionError("");
    setSkillPickerOpen(false);
    setDocumentPickerOpen(false);
    if (replace) {
      window.history.replaceState(null, "", path);
    } else {
      window.history.pushState(null, "", path);
    }
  }

  function requestNavigation(action: () => void) {
    const proceed = () => {
      setDraft("");
      files.clear();
      skills.clear();
      setDocuments((current) => ({...current, [draftKey]: []}));
      action();
    };
    if (guarded) {
      guarded(proceed);
    } else {
      proceed();
    }
  }

  function selectConversation(conversation: Conversation) {
    if (conversation.status !== "deleted" && conversation.id !== conversationId) {
      requestNavigation(() => navigate(`${base}/conversations/${encodeURIComponent(conversation.id)}`));
    }
  }

  function startNewTask(agent = detail?.agentId ?? selectedAgent, selected?: Employee) {
    requestNavigation(() => {
      approvalPolicy.resetDraft();
      model.resetDraft();
      project.resetDraft();
      setChosenEmployee(selected ?? (displayEmployee?.agentId === agent ? displayEmployee : null));
      navigate(`${base}/new-task${agent ? `?agent=${encodeURIComponent(agent)}` : ""}`);
      if (!agent) {
        setPickerOpen(true);
      }
    });
  }

  function startSkill(skill: SkillOption, agent: string) {
    requestNavigation(() => {
      approvalPolicy.resetDraft();
      model.resetDraft();
      project.resetDraft();
      skills.forNew(skill, agent);
      navigate(`${base}/new-task?agent=${encodeURIComponent(agent)}&skill=${encodeURIComponent(skill.versionId)}`);
    });
  }

  async function submitMessageContent(text = draft) {
    const content = text.trim();
    if (!model.ready || project.saving) {
      return;
    }
    if (approvalPolicy.saving || stream.state?.snapshot.activeRun || (!content && !files.fileIds.length) || !sourcesReady || pending.current || !canRun || skills.error || skills.loading || (conversationId ? !detail?.canContinue : !employee.data?.canRun)) {
      return;
    }
    pending.current = true;
    setSubmitting(true);
    setActionError("");
    const route = currentRoute.current;
    try {
      const input = {
        ...textInput(content),
        skillVersionIds: skills.selected.map((skill) => skill.versionId),
        attachmentIds: files.fileIds,
        modelSelection: model.configurable ? model.selection : null,
        knowledgeReferences: selectedDocuments.map((document) => ({
          documentId: document.documentId,
          generation: document.generation
        }))
      };
      const response = await mutation.current.run<RunAccepted>(conversationId ? `${conversationPath(enterpriseId, conversationId)}/messages` : conversationPath(enterpriseId), {
        method: "POST",
        body: conversationId ? input : {
          agentId: selectedAgent,
          input,
          approvalPolicy: approvalPolicy.value,
          projectId: project.selected?.id ?? null
        },
      });
      list.refresh();
      if (!conversationId) {
        approvalPolicy.resetDraft();
        model.resetDraft();
        project.resetDraft();
      }
      setDrafts((current) => ({...current, [draftKey]: ""}));
      skills.clear();
      files.clear();
      setDocuments((current) => ({...current, [draftKey]: []}));
      if (currentRoute.current !== route) {
        return;
      }
      setDraft("");
      if (conversationId) {
        stream.reload();
      } else {
        navigate(`${base}/conversations/${encodeURIComponent(response.conversationId)}`, true);
      }
    } catch (failed) {
      if (currentRoute.current === route) {
        setActionError(errorMessage(failed, uiText("消息未能发送，请重试。")));
      }
    } finally {
      pending.current = false;
      setSubmitting(false);
    }
  }

  async function stop() {
    const run = stream.state?.snapshot.activeRun;
    if (!run || stopping) {
      return;
    }
    setStopping(true);
    setActionError("");
    try {
      await stopMutation.current.run(`${runPath(enterpriseId, run.id)}/cancel`, {method: "POST"});
      stream.reload();
    } catch (failed) {
      setActionError(errorMessage(failed, uiText("暂时无法停止，请重试。")));
    } finally {
      setStopping(false);
    }
  }

  async function retry(runId: string) {
    if (!model.ready || model.saving || approvalPolicy.saving || project.saving) {
      return false;
    }
    if (pending.current) {
      return false;
    }
    pending.current = true;
    setSubmitting(true);
    setActionError("");
    try {
      const run = await loadRun(enterpriseId, runId);
      if (!run.canRetry) {
        setActionError(run.errorMessage || uiText("此任务当前不能重新执行。"));
        return false;
      }
      await retryMutation.current.run(`${runPath(enterpriseId, runId)}/retry`, {method: "POST"});
      changed();
      return true;
    } catch (failed) {
      setActionError(errorMessage(failed, uiText("暂时无法重新执行，请重试。")));
      return false;
    } finally {
      pending.current = false;
      setSubmitting(false);
    }
  }

  async function loadOlder() {
    await preserveHistoryPosition(stream.loadOlder);
  }

  return {
    list,
    stream,
    steps,
    detail,
    messages,
    employee,
    displayEmployee,
    employeeAppearance,
    employeeLoading,
    composerHandoff: Boolean(arrival),
    focusComposerAfterSend: Boolean(arrival?.focusInput),
    employeeReady: Boolean(conversationId || employee.data),
    selectedAgent,
    conversationId,
    draft,
    setDraft,
    submitting,
    stopping,
    actionError,
    sidebarOpen,
    setSidebarOpen,
    pickerOpen,
    setPickerOpen,
    conversationRef,
    shouldStickToBottomRef,
    scrollToBottom,
    awayFromBottom,
    hasUnseenContent,
    selectConversation,
    startNewTask,
    submitMessageContent,
    stop,
    retry,
    changed,
    loadOlder,
    project,
    afterComposerTransition: handoff?.afterTransition,
    approvalPolicy: {...approvalPolicy, disabled: approvalPolicy.disabled || project.saving},
    model: {...model, disabled: model.disabled || project.saving},
    skills,
    skillPickerOpen,
    setSkillPickerOpen,
    startSkill,
    files,
    attachmentsEnabled,
    displayAttachmentsEnabled: conversationId && stream.state ? attachmentsEnabled : Boolean(displayEmployee?.attachmentsEnabled),
    sourceError,
    selectedDocuments,
    documentPickerOpen,
    setDocumentPickerOpen,
    chooseDocuments: (value: DocumentOption[]) => setDocuments((current) => ({...current, [draftKey]: value})),
    canSubmit: canRun && model.ready && !project.saving && !approvalPolicy.saving && !stream.state?.snapshot.activeRun && !submitting && !skills.error && !skills.loading && sourcesReady && (conversationId ? Boolean(detail?.canContinue) : Boolean(employee.data?.canRun)),
    title: detail?.title ?? (conversationId ? uiText("对话任务") : uiText("新对话")),
    agentName: detail?.agentName ?? displayEmployee?.name ?? "",
    newTaskPath: `${base}/new-task`
  };
}
