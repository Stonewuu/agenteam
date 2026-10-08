"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";


import {AssistantRuntimeProvider, useExternalStoreRuntime} from "@assistant-ui/react";
import {useRouter} from "next/navigation";
import {toast} from "@/components/ui/toast";
import {useCallback, useEffect, useRef, useState} from "react";
import {useComposerSelection} from "../hooks/use-composer-selection";
import type {ComposerSelectionContext} from "../lib/composer-selection";
import {useConversationNavigation} from "./conversation-navigation-context";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";

import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import type {Employee} from "@/features/employee/types/employee";
import type {Conversation} from "../types/execution";
import {appendMessageText, toAssistantMessage} from "../lib/conversation-ui-adapter";
import {useConversationWorkspace} from "../hooks/use-conversation-workspace";
import {DocumentPickerDialog} from "@/features/knowledge/components/document-picker-dialog";

import {ConversationPane} from "./conversation-pane";
import {EmployeePicker} from "./employee-picker";
import {ConversationManagementDialog} from "./conversation-management-dialog";
import {SkillPickerDialog} from "@/features/skill/components/skill-picker-dialog";
import styles from "./agent-workspace.module.css";
import controls from "./conversation-controls.module.css";
import {IconDots, IconDownload, IconSidebarRight, IconStar} from "@/components/ui/icons";
import {useConversationSidebar} from "../hooks/use-conversation-sidebar";
import {ConversationSidebar, type ConversationSidebarHandle} from "./conversation-sidebar";
import {ConversationFileProvider} from "@/features/file/components/conversation-file-context";
import {createFilePreviewTab} from "@/features/file/components/conversation-file-preview-tab";
import type {ConversationFileTarget} from "@/features/file/types/conversation-files";
import sidebarStyles from "./conversation-sidebar.module.css";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {conversationPath} from "../api/conversation-api";
import ui from "@/components/ui/surface.module.css";

import {NotificationOpenedMarker} from "@/features/notification/components/notification-opened-marker";
import {MemoryEditor} from "@/features/memory/components/memory-editor";
import type {MemoryContext, MemorySource} from "@/features/memory/types/memory";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {ExportControl} from "@/features/export/components/export-control";


import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";


export default function ConversationWorkspace({user, context}: {
  user: IdentityUser; context: EnterpriseContext;
}) {
  const uiText = useT();
  const enterprise = context.enterprise.id;
  const canRun = context.permissions.includes("agent.run");
  const router = useRouter();
  const canUseSkills = canRun && context.permissions.includes("skill.use");
  const canUseKnowledge = canRun && context.permissions.includes("knowledge.search");
  const navigation = useConversationNavigation();
  const workspace = useConversationWorkspace(user.id, enterprise, canRun, canUseSkills, canUseKnowledge, navigation?.list, context.permissions.includes("conversation.manage"));
  const registerNewTaskAction = navigation?.registerNewTaskAction;
  const startNewTask = workspace.startNewTask;
  useEffect(() => registerNewTaskAction?.(startNewTask), [registerNewTaskAction, startNewTask]);
  const composerInput = useRef<HTMLTextAreaElement>(null);
  const sidebarToggle = useRef<HTMLButtonElement>(null);
  const sidebarContainer = useRef<HTMLDivElement>(null);
  const sidebarPanels = useRef<ConversationSidebarHandle>(null);
  const sidebar = useConversationSidebar(user.id, enterprise, sidebarContainer, workspace.conversationId);
  const setSidebarOpen = sidebar.setOpen;
  const closeSidebar = useCallback(() => {
    setSidebarOpen(false);
    sidebarToggle.current?.focus({preventScroll: true});
  }, [setSidebarOpen]);
  const canViewFiles = context.permissions.includes("conversation.view");
  const openFile = useCallback((file: ConversationFileTarget) => {
    sidebarPanels.current?.openTab(createFilePreviewTab(file));
    setSidebarOpen(true);
  }, [setSidebarOpen]);
  const setSidebarTab = sidebar.setTab;
  const fileActivityKey = workspace.stream.fileActivityKey;
  useEffect(() => {
    if (canViewFiles && fileActivityKey) {
      setSidebarTab("context");
      setSidebarOpen(true);
    }
  }, [canViewFiles, fileActivityKey, setSidebarOpen, setSidebarTab]);
  const selection = useComposerSelection(composerInput, workspace.setDraft, () => {
    workspace.setPickerOpen(false);
    workspace.setSkillPickerOpen(false);
    workspace.setDocumentPickerOpen(false);
  });
  const favorite = useFormAction();
  useBlockNavigation(Boolean(workspace.draft) || workspace.files.items.length > 0 || workspace.selectedDocuments.length > 0, workspace.submitting);
  const {conversationRef, ...view} = workspace;
  const [manage, setManage] = useState<Conversation | null>(null);
  const [exportOpen, setExportOpen] = useState(false);
  const [nextEmployee, setNextEmployee] = useState<Employee | null>(null);
  const [memorySource, setMemorySource] = useState<{ agentId: string; source: MemorySource } | null>(null);
  const latestMemoryMessage = workspace.messages.filter((message) => message.role === "assistant" && ["completed", "failed", "cancelled"].includes(message.status)).at(-1);
  const memoryAgent = workspace.detail?.agentId;
  const memoryContext = useApiQuery<MemoryContext>(user.preferences.memoryEnabled && canRun && workspace.detail?.canContinue && memoryAgent && latestMemoryMessage
    ? organizationPath(enterprise, `/agents/${encodeURIComponent(memoryAgent)}/memories/context?sourceMessageId=${encodeURIComponent(latestMemoryMessage.id)}`) : null, user.preferences.revision);
  const runtime = useExternalStoreRuntime({
    messages: workspace.messages, isLoading: workspace.stream.loading,
    isRunning: Boolean(workspace.stream.state?.snapshot.activeRun), onNew: async (message) => {
      await workspace.submitMessageContent(appendMessageText(message));
    }, convertMessage: toAssistantMessage
  });
  const active = workspace.stream.state?.snapshot.activeRun;
  const canStop = active?.mode === "preview" ? context.permissions.includes("agent.preview") : canRun;

  function selected(employee: Employee) {
    selection.consume();
    workspace.setPickerOpen(false);
    if (employee.agentId === (workspace.detail?.agentId ?? workspace.selectedAgent)) {
      requestAnimationFrame(() => composerInput.current?.focus());
      return;
    }
    if (workspace.conversationId) {
      setNextEmployee(employee);
    } else {
      workspace.startNewTask(employee.agentId, employee);
    }
  }

  function showPicker(kind: "employee" | "skill" | "document", source?: ComposerSelectionContext) {
    selection.prepare(source);
    workspace.setPickerOpen(kind === "employee");
    workspace.setSkillPickerOpen(kind === "skill");
    workspace.setDocumentPickerOpen(kind === "document");
  }

  function toggleFavorite() {
    const detail = workspace.detail;
    if (!detail) {
      return;
    }
    void favorite.execute(async () => {
      await favorite.mutation.run(conversationPath(enterprise, detail.id), {
        method: "PATCH",
        revision: detail.revision,
        body: {favorite: !detail.favorite}
      });
      workspace.changed();
    }, "");
  }

  return <ConversationFileProvider enterprise={enterprise} conversation={workspace.conversationId}
                                   openFile={canViewFiles ? openFile : undefined}><AssistantRuntimeProvider
    runtime={runtime}>
    <div className={`${styles.shell} ${sidebarStyles.layout}`} ref={sidebarContainer}
         data-sidebar-open={canViewFiles && sidebar.open} data-sidebar-compact={sidebar.compact}
         data-sidebar-full={canViewFiles && sidebar.open && sidebar.full && !sidebar.compact}>
      <div className={styles.main} data-conversation-main
           inert={canViewFiles && sidebar.open && sidebar.full && !sidebar.compact}>
        <header className={styles.mainTopbar}>
          <div className={controls.heading}><h1 title={workspace.title}>{workspace.title}</h1></div>
          <div className={styles.accountActions}>
            <div className={controls.desktopActions}>
              {workspace.detail && context.permissions.includes("conversation.manage") &&
                <Button className="icon-button" type="button"
                        aria-label={workspace.detail.favorite ? uiText("取消收藏对话") : uiText("收藏对话")}
                        aria-pressed={workspace.detail.favorite} disabled={favorite.busy}
                        onClick={toggleFavorite}><IconStar size={19}
                                                           variant={workspace.detail.favorite ? "Bold" : "Linear"}/></Button>}
              {workspace.detail && context.permissions.includes("conversation.export") &&
                <Button className="icon-button" type="button" aria-label={uiText("导出对话")}
                        onClick={() => setExportOpen(true)}><IconDownload size={19}/></Button>}
              {workspace.detail && context.permissions.includes("conversation.manage") &&
                <Button className="icon-button" aria-label={uiText("管理当前对话")}
                        onClick={() => setManage(workspace.detail!)}><IconDots size={20}/></Button>}
            </div>
            {workspace.detail && (context.permissions.includes("conversation.manage") || context.permissions.includes("conversation.export")) &&
              <div className={controls.mobileActions}><DropdownMenu><DropdownMenuTrigger
                render={<Button className="icon-button" aria-label={uiText("更多对话操作")}><IconDots
                  size={20}/></Button>}/><DropdownMenuContent align="end">
                {workspace.detail && context.permissions.includes("conversation.manage") &&
                  <DropdownMenuItem disabled={favorite.busy} onClick={toggleFavorite}><IconStar
                    size={17}/>{workspace.detail.favorite ? uiText("取消收藏") : uiText("收藏对话")}</DropdownMenuItem>}
                {workspace.detail && context.permissions.includes("conversation.export") &&
                  <DropdownMenuItem onClick={() => setExportOpen(true)}><IconDownload size={17}/>{uiText("导出对话")}
                  </DropdownMenuItem>}
                {workspace.detail && context.permissions.includes("conversation.manage") &&
                  <DropdownMenuItem onClick={() => setManage(workspace.detail!)}><IconDots
                    size={17}/>{uiText("管理当前对话")}</DropdownMenuItem>}
              </DropdownMenuContent></DropdownMenu></div>}
            {canViewFiles && (!sidebar.open || sidebar.compact) &&
              <span className={sidebarStyles.toggleSpace} aria-hidden="true"/>}
          </div>
        </header>
        <NotificationOpenedMarker enterpriseId={enterprise} targetType="conversation"
                                  targetId={workspace.stream.state?.snapshot.conversation.id ?? null}/>
        {favorite.error && <p className={ui.error} role="alert">{localizeUiMessage(favorite.error ?? "", uiText)}</p>}
        <ConversationPane enterprise={enterprise} canRun={canRun} canStop={canStop} canUseSkills={canUseSkills}
                          canUseKnowledge={canUseKnowledge} workspace={view} conversationRef={conversationRef}
                          composerInput={composerInput} onChooseEmployee={(source) => showPicker("employee", source)}
                          onChooseSkills={(source) => showPicker("skill", source)}
                          onChooseDocuments={(source) => showPicker("document", source)}
                          onMemory={memoryAgent && memoryContext.data?.canSave && !memoryContext.loading ? (message, text) => setMemorySource({
                            agentId: memoryAgent,
                            source: {sourceMessageId: message.id, text}
                          }) : undefined}/>
      </div>
      {canViewFiles && <ConversationSidebar ref={sidebarPanels}
                                            key={(workspace.conversationId ?? "new") + ":" + (workspace.project.projectId ?? "")}
                                            id={sidebar.id} enterprise={enterprise}
                                            conversation={workspace.conversationId} open={sidebar.open}
                                            compact={sidebar.compact}
                                            container={sidebarContainer}
                                            onRestoreConversation={sidebar.restoreConversation}
                                            width={sidebar.width} maximumWidth={sidebar.maximumWidth}
                                            onWidthChange={sidebar.setWidth} onResizeEnd={sidebar.finishResize}
                                            tab={sidebar.tab} onTabChange={sidebar.setTab} running={Boolean(active)}
                                            refreshKey={(workspace.stream.state?.latestRun?.id ?? "") + ":" + (workspace.stream.state?.latestRun?.status ?? "") + ":" + (workspace.stream.fileRefreshKey ?? "")}
                                            onClose={closeSidebar}/>}
      {canViewFiles && <Button ref={sidebarToggle} className={`icon-button ${sidebarStyles.sharedToggle}`} type="button"
                               aria-label={sidebar.open ? uiText("收起对话侧栏") : uiText("展开对话侧栏")}
                               title={sidebar.open ? uiText("收起对话侧栏") : uiText("文件与上下文")}
                               aria-expanded={sidebar.open} aria-controls={sidebar.id}
                               onClick={() => sidebar.setOpen(!sidebar.open)}><IconSidebarRight size={20}/></Button>}
    </div>
    {exportOpen && workspace.detail &&
      <Dialog title={uiText("导出对话")} onClose={() => setExportOpen(false)}><ExportControl enterpriseId={enterprise}
                                                                                             userId={user.id}
                                                                                             path={organizationPath(enterprise, `/conversations/${encodeURIComponent(workspace.detail.id)}/export`)}
                                                                                             title={uiText("导出对话")}
                                                                                             description={uiText("将已保存的对话内容保存为表格文件。")}/></Dialog>}
    {memorySource &&
      <MemoryEditor enterpriseId={enterprise} agentId={memorySource.agentId} initial={null} source={memorySource.source}
                    onClose={() => setMemorySource(null)} onReload={() => {
        setMemorySource(null);
        memoryContext.retry();
      }} onSaved={() => {
        setMemorySource(null);
        toast.success(uiText("偏好已保存。"), {
          actionProps: {
            children: uiText("管理偏好"),
            onClick: () => router.push(`/enterprises/${encodeURIComponent(enterprise)}/memories/${encodeURIComponent(memorySource.agentId)}`)
          }
        });
      }}/>}
    {workspace.pickerOpen &&
      <EmployeePicker enterprise={enterprise} selectedId={workspace.detail?.agentId ?? workspace.selectedAgent}
                      anchor={selection.context?.anchor ?? composerInput} initialQuery={selection.context?.query}
                      returnFocus={composerInput} onClose={() => workspace.setPickerOpen(false)} onSelect={selected}
                      showMarket={context.permissions.includes("agent.market_view")}/>}
    {workspace.skillPickerOpen && canUseSkills && (workspace.detail?.agentId ?? workspace.selectedAgent) &&
      <SkillPickerDialog enterprise={enterprise}
                         agent={(workspace.detail?.agentId ?? workspace.selectedAgent)!}
                         conversation={workspace.conversationId} selected={workspace.skills.selected}
                         anchor={selection.context?.anchor} returnFocus={composerInput}
                         initialQuery={selection.context?.query}
                         onChange={(value) => {
                           workspace.skills.choose(value);
                           selection.consume();
                         }}
                         onClose={() => workspace.setSkillPickerOpen(false)}/>}
    {workspace.documentPickerOpen && canUseKnowledge && (workspace.detail?.agentId ?? workspace.selectedAgent) &&
      <DocumentPickerDialog enterpriseId={enterprise}
                            agentId={(workspace.detail?.agentId ?? workspace.selectedAgent)!}
                            conversationId={workspace.conversationId} selected={workspace.selectedDocuments}
                            anchor={selection.context?.anchor} returnFocus={composerInput}
                            initialQuery={selection.context?.query}
                            onChange={(value) => {
                              workspace.chooseDocuments(value);
                              selection.consume();
                            }} onChooseEmployee={() => showPicker("employee", selection.context ?? undefined)}
                            onClose={() => workspace.setDocumentPickerOpen(false)}/>}
    {nextEmployee &&
      <Dialog title={uiText("使用“{0}”开始新对话？", [nextEmployee.name])} onClose={() => setNextEmployee(null)}>
        <p className={ui.description}>{uiText("确认后将开始一段新对话。")}</p><DialogActions className={ui.footer}>
        <DialogCancel className={ui.button}>{uiText("取消")}</DialogCancel>
        <DialogAction className={ui.primary} onAction={(close) => close(() => {
          workspace.startNewTask(nextEmployee.agentId, nextEmployee);
          setNextEmployee(null);
        })}>{uiText("开始新对话")}</DialogAction>
      </DialogActions>
      </Dialog>}
    {manage && <ConversationManagementDialog key={`${manage.id}:${manage.revision}`} enterprise={enterprise}
                                             conversation={manage} onClose={() => setManage(null)}
                                             onChanged={(action, value) => {
                                               workspace.changed();
                                               if (action === "deleted" && workspace.conversationId === manage.id) {
                                                 workspace.startNewTask();
                                               }
                                               if (action === "restored" && value) {
                                                 workspace.selectConversation(value);
                                               }
                                             }}/>}
  </AssistantRuntimeProvider></ConversationFileProvider>;
}
