"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useCallback, useEffect, useEffectEvent, useId, useMemo, useRef, useState} from "react";
import {usePathname, useSearchParams} from "next/navigation";
import {workspaceParts} from "@/features/auth/lib/workspace-path";
import {ApiMutation, errorMessage} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {Employee} from "@/features/employee/types/employee";
import {conversationPath, loadRun, runPath, textInput} from "../api/conversation-api";
import type {Conversation, RunAccepted} from "../types/execution";
import {displayMessages} from "../lib/conversation-display";
import {createConversationSubmission, mergeConversationSubmissions, observeConversationSubmissions, type ConversationSubmission} from "../lib/conversation-submissions";
import {useConversationList} from "./use-conversation-list";
import {useConversationMetadata} from "./use-conversation-metadata";
import {useConversationStream} from "./use-conversation-stream";
import {useConversationResultViewed} from "./use-conversation-result-viewed";
import {useConversationScroll} from "./use-conversation-scroll";
import {conversationReadingPosition} from "../lib/conversation-reading";
import {useConversationStepHistory} from "./use-conversation-step-history";
import {useConversationSkills} from "@/features/skill/hooks/use-conversation-skills";
import type {SkillOption} from "@/features/skill/types/skill";
import type {DocumentOption} from "@/features/knowledge/types/knowledge";
import {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import type {HomeSummary} from "@/features/workspace/types/workspace";
import {useBlockNavigation, useGuardedNavigation} from "@/features/workspace/components/navigation-guard";
import {useConversationApprovalPolicy} from "./use-conversation-approval-policy";
import {useConversationModel} from "./use-conversation-model";
import {useComposerHandoff} from "../components/composer-handoff-context";
import type {ComposerHandoff} from "../lib/composer-handoff";
import {useConversationProject} from "@/features/project/hooks/use-conversation-project";

export type ConversationLocation = {
  pathname: string;
  search?: string;
  active?: boolean;
  navigate: (path: string, replace: boolean) => void;
};

export function useConversationWorkspace(userId: string, enterpriseId: string, canRun: boolean, canUseSkills: boolean, canUseKnowledge: boolean, sharedList?: ReturnType<typeof useConversationList>, canManage = false, location?: ConversationLocation) {
  const uiText = useT();
  const browserPathname = usePathname();
  const browserParams = useSearchParams();
  const pathname = location?.pathname ?? browserPathname;
  const embedded = location !== undefined;
  const search = location?.search;
  const params = useMemo(() => embedded ? new URLSearchParams(search ?? "") : browserParams,
    [embedded, search, browserParams]);
  const scope = useId();
  const guarded = useGuardedNavigation(embedded ? scope : undefined);
  const parts = workspaceParts(pathname);
  const routeConversationId = parts[0] === "conversations" && parts[1] ? decodeURIComponent(parts[1]) : null;
  const handoff = useComposerHandoff();
  const handoffValue = handoff?.value;
  const sameHandoffScope = handoffValue?.userId === userId && handoffValue.enterpriseId === enterpriseId;
  const pendingArrival = Boolean(sameHandoffScope && parts[0] === "new-task"
    && params.get("submission") === handoffValue?.submission.id);
  const arrival = sameHandoffScope && (pendingArrival || routeConversationId && handoffValue?.conversationId === routeConversationId)
    ? handoffValue : null;
  const conversationId = routeConversationId ?? (pendingArrival ? arrival?.conversationId ?? null : null);
  const creating = Boolean(arrival && arrival.status !== "accepted");
  const creationPending = arrival?.status === "sending";
  const recent = useApiQuery<HomeSummary>(!conversationId && !params.get("agent") && canRun ? organizationPath(enterpriseId, "/home") : null);
  const selectedAgent = conversationId ? null : params.get("agent") ?? recent.data?.employees[0]?.agentId ?? null;
  const localList = useConversationList(enterpriseId, !sharedList, userId);
  const list = sharedList ?? localList;
  const stream = useConversationStream(enterpriseId, conversationId);
  const metadataItems = list.items.filter(value => value.mode === "normal" && !value.collaboration)
    .map(value => ({id: value.id, revision: value.revision}));
  if (conversationId && !metadataItems.some(value => value.id === conversationId)) {
    metadataItems.push({id: conversationId, revision: stream.state?.snapshot.conversation.revision ?? ""});
  }
  useConversationMetadata(enterpriseId, metadataItems, location?.active !== false, values => {
    stream.updateMetadata(values);
    list.updateMetadata(values);
  });
  useConversationResultViewed(enterpriseId, conversationId, stream.state?.latestRun,
    location?.active !== false && Boolean(stream.state && !stream.state.replaying && !stream.loading && !stream.error));
  const steps = useConversationStepHistory(enterpriseId, stream.state?.snapshot.messages, stream.historyVersion,
    stream.historySequence, stream.state?.snapshot.stepHistory);
  const detail = stream.state?.snapshot.conversation;
  const skills = useConversationSkills(userId, enterpriseId, detail?.agentId ?? selectedAgent, conversationId, params.get("skill"), canUseSkills);
  const [skillPickerOpen, setSkillPickerOpen] = useState(false);
  const [chosenEmployee, setChosenEmployee] = useState<Employee | null>(null);
  const employeeId = detail ? detail.agentId : selectedAgent ?? arrival?.employee.agentId ?? null;
  const preloadedEmployee = !conversationId && employeeId
    ? (chosenEmployee?.agentId === employeeId ? chosenEmployee
      : recent.data?.employees?.find((value) => value.agentId === employeeId)
        ?? (arrival?.employee.agentId === employeeId ? arrival.employee : null))
    : null;
  // 已有对话由会话快照提供外观与可用状态，新对话优先复用首页/选择器已读取的员工详情。
  const employee = useApiQuery<Employee>(!conversationId && employeeId && !preloadedEmployee
    ? organizationPath(enterpriseId, `/employees/${encodeURIComponent(employeeId)}`) : null);
  const resolvedEmployee = employee.data ?? preloadedEmployee;
  const employeeLoading = !conversationId && (recent.loading || (!preloadedEmployee && employee.loading));
  const displayEmployee = resolvedEmployee ?? (arrival?.employee.agentId === employeeId ? arrival.employee : null);
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
  const launchedHandoff = useRef<string | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const mutation = useRef(new ApiMutation());
  const stopMutation = useRef(new ApiMutation());
  const queueMutation = useRef(new ApiMutation());
  const retryMutation = useRef(new ApiMutation());
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [submissions, setSubmissions] = useState<ConversationSubmission[]>([]);
  const draftKey = `${userId}:${enterpriseId}:${conversationId ?? `new:${selectedAgent ?? ""}`}`;
  const savedMessages = useMemo(() => displayMessages(stream.state), [stream.state]);
  const arrivingSubmission = arrival?.submission;
  const existingSubmission = submissions.find(value => value.id === arrivingSubmission?.id);
  const handoffChanged = arrivingSubmission?.scope === draftKey && (!existingSubmission
    || existingSubmission.scope !== arrivingSubmission.scope || existingSubmission.accepted !== arrivingSubmission.accepted
    || existingSubmission.failed !== arrivingSubmission.failed);
  const handedSubmissions = handoffChanged ? [...submissions.filter(value => value.id !== arrivingSubmission.id),
    {...arrivingSubmission, inputObserved: existingSubmission?.inputObserved ?? false,
      replyObserved: existingSubmission?.replyObserved ?? false}] : submissions;
  const observedSubmissions = observeConversationSubmissions(handedSubmissions, draftKey, savedMessages);
  if (observedSubmissions !== submissions) {
    setSubmissions(observedSubmissions);
  }
  const messages = useMemo(() => mergeConversationSubmissions(savedMessages, observedSubmissions, draftKey),
    [savedMessages, observedSubmissions, draftKey]);
  const sending = observedSubmissions.some(value => value.scope === draftKey && !value.accepted && !value.failed);
  const scrollMessages = useMemo(() => messages.map(message => ({
    id: message.id, content: message.content, status: message.status,
    blocks: stream.state?.snapshot.messages.find(saved => saved.id === message.id)?.blocks
  })), [messages, stream.state]);
  const files = useFileUploads(enterpriseId, "attachment", draftKey);
  const [documents, setDocuments] = useState<Record<string, DocumentOption[]>>({});
  const [documentPickerOpen, setDocumentPickerOpen] = useState(false);
  const selectedDocuments = documents[draftKey] ?? [];
  const attachmentsEnabled = conversationId ? Boolean(stream.state?.snapshot.attachmentsEnabled) : Boolean(resolvedEmployee?.attachmentsEnabled);
  const sourceError = files.items.length && !attachmentsEnabled ? uiText("当前员工不接收附件，请移除所选文件。")
    : selectedDocuments.length && !canUseKnowledge ? uiText("当前无法引用资料，请移除所选资料。") : "";
  const sourcesReady = !sourceError && (!files.items.length || files.ready);
  const draft = drafts[draftKey] ?? "";
  useBlockNavigation(Boolean(draft) || files.items.length > 0 || selectedDocuments.length > 0 || arrival?.status === "failed",
    submitting || creationPending, embedded ? scope : undefined);
  const {
    conversationRef,
    shouldStickToBottomRef,
    scrollToBottom,
    preserveHistoryPosition,
    awayFromBottom,
    hasUnseenContent
  } = useConversationScroll(draftKey, scrollMessages, stream.state?.steps);
  const currentRoute = useRef(pathname + "?" + params.toString());
  const historySnapshot = stream.state?.snapshot;
  useEffect(() => {
    const position = conversationReadingPosition(draftKey);
    if (position && !position.atBottom && position.anchorId && historySnapshot?.conversation.id === conversationId
      && !historySnapshot.messages.some(message => message.id === position.anchorId) && historySnapshot.hasOlderMessages
      && !stream.loadingOlder && !stream.loading && !stream.error) {
      void stream.loadOlder();
    }
  }, [draftKey, conversationId, historySnapshot, stream]);
  const base = ``;

  useEffect(() => {
    currentRoute.current = pathname + "?" + params.toString();
  }, [pathname, params]);

  const refreshList = list.refresh;
  const reloadConversation = stream.reload;
  const acceptedConversationId = pendingArrival ? arrival?.conversationId : null;
  useEffect(() => {
    if (acceptedConversationId) {
      const path = `/conversations/${encodeURIComponent(acceptedConversationId)}`;
      refreshList();
      if (location) {
        location.navigate(path, true);
      } else {
        window.history.replaceState(null, "", path);
      }
    }
  }, [acceptedConversationId, refreshList, location]);
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
    busy: submitting || creating || Boolean(stream.state?.snapshot.activeRun),
    onChanged: changed,
    initialPolicy: arrival?.approvalPolicy
  });
  const model = useConversationModel({
    enterprise: enterpriseId,
    agentId: detail?.agentId ?? selectedAgent,
    draftKey,
    conversation: detail,
    enabled: canRun && (!conversationId || Boolean(detail)),
    busy: submitting || creating || approvalPolicy.saving || Boolean(stream.state?.snapshot.activeRun),
    onChanged: changed,
    initialOptions: arrival?.modelOptions
  });
  const project = useConversationProject({
    enterprise: enterpriseId,
    draftKey,
    conversationId,
    conversation: detail,
    enabled: canRun,
    busy: submitting || creating || approvalPolicy.saving || model.saving || Boolean(stream.state?.snapshot.activeRun),
    onChanged: changed,
    initialProject: arrival?.project
  });
  useEffect(() => {
    if (arrival?.conversationId && detail && (displayEmployee || conversationId) && (model.loaded || !canRun)) {
      finishHandoff?.(arrival.conversationId);
    }
  }, [arrival, detail, displayEmployee, conversationId, finishHandoff, stream.error, model.loaded, canRun]);
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
  const observedRunRef = useRef<{ id: string; status: string } | null>(null);
  useEffect(() => {
    if (!latestRunId || !latestRunStatus) {
      return;
    }
    const previous = observedRunRef.current;
    observedRunRef.current = {id: latestRunId, status: latestRunStatus};
    const terminal = ["completed", "failed", "cancelled"];
    // 仅在当前页面观测到执行从未完成转为终态时刷新侧栏列表，打开已完成历史对话不重复刷列表。
    if (previous && previous.id === latestRunId && !terminal.includes(previous.status) && terminal.includes(latestRunStatus)) {
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
    if (location) {
      location.navigate(path, replace);
      return;
    }
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
      if (creating && arrival) {
        handoff?.discard(arrival.submission.id);
      }
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

  const activeRun = stream.state?.snapshot.activeRun;
  const canQueue = Boolean(activeRun?.mode === "interactive" && ["queued", "running", "waiting_approval"].includes(activeRun.status)
    && detail?.mode === "normal" && detail.status === "active" && !detail.collaborationTaskId && canRun);
  const canSendHere = conversationId ? (activeRun ? canQueue : Boolean(detail?.canContinue)) : Boolean(resolvedEmployee?.canRun);

  async function submitMessageContent(text = draft, initial?: ComposerHandoff) {
    const content = initial?.input.text ?? text.trim();
    if (pending.current || !canRun) {
      return;
    }
    if (!initial && (creating || !model.ready || project.saving || approvalPolicy.saving
      || (!content && !files.fileIds.length) || !sourcesReady || skills.error || skills.loading || !canSendHere)) {
      return;
    }
    pending.current = true;
    setSubmitting(true);
    setActionError("");
    const route = currentRoute.current;
    const submission = initial ? {...initial.submission, failed: false} : createConversationSubmission({
      scope: draftKey, text: content, messages, queued: canQueue,
      attachments: files.items.flatMap(item => item.reference?.status === "ready" ? [item.reference] : []),
      labels: [...skills.selected.map(skill => skill.name), ...selectedDocuments.map(document => document.name)]
    });
    setSubmissions(current => [...current.filter(value => value.id !== submission.id), submission]);
    if (initial) {
      handoff?.update(submission);
    }
    setDraft("");
    scrollToBottom();
    try {
      const input = initial?.input ?? {
        ...textInput(content),
        skillVersionIds: skills.selected.map((skill) => skill.versionId),
        attachmentIds: files.fileIds,
        modelSelection: model.configurable ? model.selection : null,
        knowledgeReferences: selectedDocuments.map((document) => ({
          documentId: document.documentId,
          generation: document.generation
        }))
      };
      const response = await mutation.current.run<RunAccepted>(conversationId ? `${conversationPath(enterpriseId, conversationId)}/messages?queue=true` : conversationPath(enterpriseId), {
        method: "POST",
        body: conversationId ? input : {
          agentId: initial?.employee.agentId ?? selectedAgent,
          input,
          approvalPolicy: initial?.approvalPolicy ?? approvalPolicy.value,
          projectId: (initial ? initial.project : project.selected)?.id ?? null
        },
      });
      const confirmed = {...submission, accepted: response, scope: `${userId}:${enterpriseId}:${response.conversationId}`};
      setSubmissions(current => current.map(value => value.id === submission.id ? confirmed : value));
      if (initial) {
        handoff?.update(confirmed);
      }
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
      if (!mounted.current || currentRoute.current !== route) {
        return;
      }
      setDraft("");
      if (conversationId) {
        stream.reload();
      } else {
        navigate(`${base}/conversations/${encodeURIComponent(response.conversationId)}`, true);
      }
    } catch (failed) {
      const message = errorMessage(failed, uiText("消息未能发送，请重试。"));
      if (initial) {
        const rejected = {...submission, failed: true};
        setSubmissions(current => current.map(value => value.id === submission.id ? rejected : value));
        handoff?.update(rejected, message);
      } else {
        setSubmissions(current => current.filter(value => value.id !== submission.id));
        setDrafts(current => ({...current, [draftKey]: text}));
        if (currentRoute.current === route) {
          setActionError(message);
        }
      }
    } finally {
      pending.current = false;
      setSubmitting(false);
    }
  }

  const sendArrivingMessage = useEffectEvent((initial: ComposerHandoff) => {
    void submitMessageContent(initial.input.text, initial);
  });
  useEffect(() => {
    if (arrival?.status === "sending" && canRun && launchedHandoff.current !== arrival.submission.id) {
      launchedHandoff.current = arrival.submission.id;
      sendArrivingMessage(arrival);
    }
  }, [arrival, canRun]);

  async function changeQueue(run: string, prioritize: boolean) {
    if (!conversationId) {
      return;
    }
    const route = currentRoute.current;
    const path = `${conversationPath(enterpriseId, conversationId)}/queue/${encodeURIComponent(run)}`;
    setActionError("");
    try {
      await queueMutation.current.run(prioritize ? `${path}/prioritize` : path, {method: prioritize ? "POST" : "DELETE"});
    } catch (error) {
      if (currentRoute.current === route) {
        setActionError(errorMessage(error, uiText("未能更新待处理消息。")));
      }
    } finally {
      if (currentRoute.current === route) {
        stream.reload();
      }
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
    employeeReady: Boolean(conversationId || resolvedEmployee),
    selectedAgent,
    conversationId,
    draft,
    setDraft,
    submitting: submitting || creating,
    sending,
    creationError: arrival?.status === "failed" ? arrival.error : "",
    retryCreation: () => {
      if (arrival?.status === "failed" && canRun) {
        void submitMessageContent(arrival.input.text, arrival);
      }
    },
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
    files: sending ? {...files, items: [], fileIds: []} : files,
    attachmentsEnabled,
    displayAttachmentsEnabled: conversationId && stream.state ? attachmentsEnabled : Boolean(displayEmployee?.attachmentsEnabled),
    sourceError,
    selectedDocuments,
    documentPickerOpen,
    setDocumentPickerOpen,
    chooseDocuments: (value: DocumentOption[]) => setDocuments((current) => ({...current, [draftKey]: value})),
    canQueue,
    changeQueue,
    canSubmit: canRun && !creating && model.ready && !project.saving && !approvalPolicy.saving && !submitting && !skills.error && !skills.loading && sourcesReady && canSendHere,
    title: detail?.title ?? (conversationId ? uiText("对话任务") : uiText("新对话")),
    agentName: detail?.agentName ?? displayEmployee?.name ?? "",
    newTaskPath: `${base}/new-task`
  };
}
