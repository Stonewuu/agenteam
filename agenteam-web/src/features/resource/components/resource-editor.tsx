"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Disclosure, DisclosureSummary} from "@/components/ui/disclosure";

import {Button} from "@/components/ui/button";
import {Fieldset} from "@/components/ui/fieldset";

import {PageHeader} from "@/components/ui/page-header";

import Link from "next/link";
import {useId, useState, useSyncExternalStore} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import {Tabs} from "@/components/ui/tabs";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {MotionPanel} from "@/components/ui/motion-panel";
import {QueryState} from "@/components/ui/query-state";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconArrowLeft, IconDeviceFloppy, IconHistory} from "@/components/ui/icons";
import {useApiQuery} from "@/lib/http/use-api-query";
import {ApiError, apiRequest, errorMessage} from "@/lib/http/api-client";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {ErrorFeedback} from "@/components/ui/error-feedback";
import {draftFrom, draftInput, newConfig, resourcePage, resourceStatus, resourceTypes} from "../lib/resource-display";
import {type EditorSection, editorSections, sectionForField} from "../lib/editor-sections";
import {mergeDraftChanges} from "../lib/merge-draft-changes";
import type {DraftWrite, PluginConfig, ResourceDetail, ResourceKind, WorkflowConfig} from "../types/resource";
import {PluginToolsPanel} from "@/features/plugin/components/plugin-tools-panel";
import {KnowledgeDocumentsPanel} from "@/features/knowledge/components/knowledge-documents-panel";
import {DataCollectionsPanel} from "@/features/data/components/data-collections-panel";
import {WorkflowPreview} from "@/features/workflow/components/workflow-preview";
import {AppearanceFields} from "./appearance-fields";
import type {ToolSource} from "@/features/plugin/types/plugin";
import {collectionSource, remoteSource, sourceTools} from "@/features/plugin/lib/plugin-collection";
import {BuiltinPluginDetails} from "@/features/plugin/components/builtin-plugin-details";
import {Section, TextField} from "./resource-fields";
import {ResourceConfigForm} from "./resource-config-form";
import {ResourceActions} from "./resource-actions";
import {ResourceVersions} from "./resource-versions";
import {TagPicker} from "./tag-picker";
import {AgentPreview} from "./agent-preview";
import {AgentIntroductionPreview} from "./agent-introduction-preview";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";

const subscribeToClient = () => () => {
};

export function ResourceEditor({enterpriseId, kind, id, permissions, permissionVersion}: {
  enterpriseId: string;
  kind: ResourceKind;
  id: string;
  permissions: string[];
  permissionVersion: string
}) {
  const uiText = useT();
  const clientReady = useSyncExternalStore(subscribeToClient, () => true, () => false);
  const params = useSearchParams();
  const builtinCode = kind === "plugin" && id === "new" ? params.get("builtin") : null;
  const builtinOptions = useApiQuery<ToolSource[]>(builtinCode ? organizationPath(enterpriseId, "/plugins/tool-sources") : null);
  const detail = useApiQuery<ResourceDetail>(id === "new" ? null : organizationPath(enterpriseId, `/resources/${encodeURIComponent(id)}`), permissionVersion);
  if (id === "new" && builtinCode) {
    return <QueryState {...builtinOptions} hasData={clientReady && Boolean(builtinOptions.data)}
                       empty={uiText("无法读取内置插件。")}>
      {builtinOptions.data?.find((plugin) => plugin.code === builtinCode) && permissions.includes("plugin.create")
        ? <EditorForm key={builtinCode} enterpriseId={enterpriseId} kind={kind} initial={null} permissions={permissions}
                      builtin={builtinOptions.data.find((plugin) => plugin.code === builtinCode)}/>
        : builtinOptions.data && <p className={ui.empty}>{uiText("此内置插件暂不可用。")}</p>}
    </QueryState>;
  }
  if (id === "new") {
    return <QueryState loading={!clientReady} error="" retry={() => {
    }} hasData={clientReady} empty="">
      {permissions.includes(`${kind}.create`) ?
        <EditorForm key={kind} enterpriseId={enterpriseId} kind={kind} initial={null} permissions={permissions}/> :
        <div className={ui.empty}>{uiText("无法访问此内容。")}</div>}
    </QueryState>;
  }
  return <QueryState {...detail} hasData={Boolean(detail.data)} empty={uiText("无法访问此内容。")}>
    {detail.data?.resource.kind === kind ? kind === "plugin" && detail.data.resource.source === "builtin" ?
      <BuiltinPluginDetails enterpriseId={enterpriseId} detail={detail.data} onChanged={detail.retry}/>
      : <EditorForm key={id} enterpriseId={enterpriseId} kind={kind} initial={detail.data}
                    permissions={permissions}/> : detail.data &&
      <div className={ui.empty}>{uiText("无法访问此内容。")}</div>}
  </QueryState>;
}

type EditorState = { detail: ResourceDetail | null; draft: DraftWrite; saved: DraftWrite };

function EditorForm({enterpriseId, kind, initial, permissions, builtin}: {
  enterpriseId: string;
  kind: ResourceKind;
  initial: ResourceDetail | null;
  permissions: string[];
  builtin?: ToolSource
}) {
  const uiText = useT();
  const router = useRouter();
  const search = useSearchParams();
  const action = useFormAction();
  const formId = useId();
  const label = localizeCatalog(resourceTypes, uiText).find((value) => value.kind === kind)!.label;
  const [state, setState] = useState<EditorState>(() => {
    const draft = initial ? draftFrom(initial) : {
      name: builtin?.name ?? "",
      description: builtin?.description ?? "",
      tagIds: [],
      config: newConfig(kind)
    };
    if (!initial && builtin && kind === "plugin") {
      draft.config = {
        ...draft.config,
        sources: [collectionSource(builtin)],
        tools: sourceTools(builtin),
        timeoutSeconds: 30
      } as PluginConfig;
    }
    return {detail: initial, draft, saved: draft};
  });
  const [section, setSection] = useState<EditorSection>(() => localizeCatalog(editorSections, uiText)[kind].find((item) => item.value === search.get("section"))?.value ?? "basic");
  const [history, setHistory] = useState(false);
  const [latest, setLatest] = useState<ResourceDetail | null>(null);
  const [source, setSource] = useState(initial);
  const [discarding, setDiscarding] = useState(false);
  const [checking, setChecking] = useState(false);
  const [lastErrors, setLastErrors] = useState(action.fieldErrors);
  const dirty = JSON.stringify(state.draft) !== JSON.stringify(state.saved);
  const editable = state.detail ? state.detail.resource.allowedActions.includes("edit") : permissions.includes(`${kind}.create`);
  useBlockNavigation(dirty, action.busy || checking);
  if (action.fieldErrors !== lastErrors) {
    setLastErrors(action.fieldErrors);
    const field = Object.keys(action.fieldErrors)[0];
    if (field) {
      setSection(sectionForField(kind, field));
    }
  }
  if (initial && initial !== source) {
    setSource(initial);
    setState((previous) => {
      if (JSON.stringify(previous.draft) !== JSON.stringify(previous.saved)) {
        return {
          ...previous, detail: previous.detail ? {
            ...previous.detail, fieldErrors: initial.fieldErrors,
            resource: {...previous.detail.resource, allowedActions: initial.resource.allowedActions}
          } : initial
        };
      }
      const draft = draftFrom(initial);
      return {detail: initial, draft, saved: draft};
    });
  }
  const apply = (detail: ResourceDetail) => {
    const draft = draftFrom(detail);
    setState({detail, draft, saved: draft});
    action.resetFeedback();
  };
  const changed = async () => {
    if (!state.detail) {
      return;
    }
    try {
      const current = await apiRequest<ResourceDetail>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(state.detail.resource.id)}`));
      if (dirty) {
        setLatest(current);
      } else {
        apply(current);
      }
    } catch (error) {
      console.error("重新读取资源失败", error);
      if (error instanceof ApiError && error.status === 404) {
        router.push(resourcePage(enterpriseId, kind));
      } else {
        action.setError(errorMessage(error));
      }
    }
  };
  const loadLatest = () => void action.execute(async () => {
    if (state.detail) {
      setLatest(await apiRequest<ResourceDetail>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(state.detail.resource.id)}`)));
    }
  }, "");
  const changeDraft = <K extends keyof DraftWrite>(key: K, value: DraftWrite[K]) => setState((previous) => ({
    ...previous,
    draft: {...previous.draft, [key]: value}
  }));
  const errors = {...state.detail?.fieldErrors, ...action.fieldErrors};
  const busy = action.busy || checking;
  const configuration = kind === "agent" || kind === "skill" && section !== "basic" || kind === "plugin" && section === "connection" || ["knowledge", "data"].includes(kind) && section === "basic";
  return <div className={styles.editor}>
    <PageHeader title={state.draft.name || uiText("新建{0}", [label])} size="small" leading={<>
      <Link className="icon-button" href={resourcePage(enterpriseId, kind)} aria-label={uiText("返回{0}列表", [label])}><IconArrowLeft
        size={20}/></Link>
      <ResourceAvatar icon={state.draft.config.icon} color={state.draft.config.color}/></>} metadata={<>
      <span
        className="badge neutral">{state.detail ? uiText(resourceStatus(state.detail.resource)) : uiText("草稿")}</span>{dirty &&
      <span className={styles.unsaved}>{uiText("有未保存的修改")}</span>}
      {state.detail?.resource.hasUnpublishedChanges && <span className="badge amber">{uiText("有未发布修改")}</span>}
    </>} actions={<div className={styles.topActions}>
      {state.detail &&
        <Button type="button" className={ui.button} onClick={() => setHistory(true)} disabled={busy}><IconHistory
          size={16}/>{uiText("历史")}</Button>}
      {kind === "agent" && state.detail?.resource.allowedActions.includes("preview") &&
        <AgentPreview enterpriseId={enterpriseId} resource={state.detail.resource} draft={state.draft}
                      saving={action.busy}/>}
      {kind === "workflow" && state.detail?.resource.allowedActions.includes("preview") &&
        <WorkflowPreview enterprise={enterpriseId} resource={state.detail.resource}
                         draft={state.draft.config as WorkflowConfig} disabled={busy}/>}
      {editable && <Button form={formId} className={ui.button} type="submit" disabled={busy}><IconDeviceFloppy
        size={16}/>{action.busy ? uiText("正在保存…") : uiText("保存草稿")}</Button>}
      {state.detail &&
        <ResourceActions enterpriseId={enterpriseId} resource={state.detail.resource} onChanged={() => void changed()}
                         dirty={dirty || busy}/>}
    </div>}/>
    <div
      className={`${styles.editorLayout} ${kind === "agent" ? styles.agentLayout : kind === "workflow" ? styles.workflowLayout : ""}`}>
      {kind !== "workflow" && <aside className={styles.editorNavigation}><Tabs value={section} onChange={setSection}
                                                                               label={uiText("{0}配置分区", [label])}
                                                                               orientation="vertical"
                                                                               variant="navigation"
                                                                               items={localizeCatalog(editorSections, uiText)[kind].map((item, index) => ({
                                                                                 ...item,
                                                                                 prefix: String(index + 1).padStart(2, "0"),
                                                                                 count: Object.keys(errors).filter((field) => sectionForField(kind, field) === item.value).length || undefined
                                                                               }))}/>{state.detail &&
        <p className={styles.lastSaved}>{uiText("最近保存")}<EnterpriseDateTime value={state.detail.resource.updatedAt}
                                                                                compact/></p>}</aside>}
      <MotionPanel className={`${styles.editorPanel} ${kind === "workflow" ? styles.workflowPanel : ""}`}
                   value={kind === "workflow" ? "workflow" : section}>
        <form id={formId} onSubmit={(event) => {
          event.preventDefault();
          void action.execute(async () => {
            const before = state.detail;
            const saved = await action.mutation.run<ResourceDetail>(organizationPath(enterpriseId, `/resources${before ? `/${encodeURIComponent(before.resource.id)}/draft` : ""}`),
              {
                method: before ? "PUT" : "POST",
                revision: before?.resource.revision,
                body: {...draftInput(kind, state.draft), ...(!before ? {kind} : {})}
              });
            apply(saved);
            if (!before && permissions.includes(`${kind}.view`)) {
              router.replace(`${resourcePage(enterpriseId, kind, saved.resource.id)}?section=${section}`, {scroll: false});
            }
          }, state.detail ? uiText("草稿已保存。") : uiText("{0}已创建。", [label]));
        }}>
          <Fieldset className={styles.editorFields} disabled={!editable || busy}>
            {kind === "workflow" ?
              <div className={styles.workflowBasics}><TextField label={uiText("工作流名称")} name="name"
                                                                value={state.draft.name}
                                                                onChange={(value) => changeDraft("name", value)}
                                                                maximum={80} required errors={errors}/><TextField
                label={uiText("简介")} name="description" value={state.draft.description}
                onChange={(value) => changeDraft("description", value)} maximum={500} errors={errors}/>
                <Disclosure><DisclosureSummary>{uiText("图标与标签")}</DisclosureSummary><AppearanceFields
                  disabled={!editable || busy} value={state.draft.config}
                  onChange={(value) => changeDraft("config", {...state.draft.config, ...value})}/><TagPicker
                  enterpriseId={enterpriseId} selected={state.draft.tagIds}
                  onChange={(value) => changeDraft("tagIds", value)} known={state.detail?.resource.tags}
                  canManage={permissions.includes("tag.manage")} readOnly={!editable}/></Disclosure>
              </div> : section === "basic" && <>
              <Section title={localizeCatalog(editorSections, uiText)[kind][0].label}><TextField label={uiText("名称")}
                                                                                                 name="name"
                                                                                                 value={state.draft.name}
                                                                                                 onChange={(value) => changeDraft("name", value)}
                                                                                                 maximum={80} required
                                                                                                 errors={errors}/>
                <TextField label={uiText("简介")} name="description" value={state.draft.description}
                           onChange={(value) => changeDraft("description", value)} maximum={500} multiline
                           errors={errors}/>
                {kind !== "agent" && <AppearanceFields disabled={!editable || busy} value={state.draft.config}
                                                       onChange={(value) => changeDraft("config", {...state.draft.config, ...value})}/>}
              </Section>
              {kind !== "agent" && <TagPicker enterpriseId={enterpriseId} selected={state.draft.tagIds}
                                              onChange={(value) => changeDraft("tagIds", value)}
                                              known={state.detail?.resource.tags}
                                              canManage={permissions.includes("tag.manage")} readOnly={!editable}/>}
            </>}
            {configuration &&
              <ResourceConfigForm section={section} enterpriseId={enterpriseId} kind={kind} config={state.draft.config}
                                  onChange={(value) => changeDraft("config", value)} permissions={permissions}
                                  errors={errors} readOnly={!editable} resource={state.detail?.resource}/>}
            {kind === "agent" && section === "basic" &&
              <Section title={uiText("图标与标签")}><AppearanceFields disabled={!editable || busy}
                                                                      value={state.draft.config}
                                                                      onChange={(value) => changeDraft("config", {...state.draft.config, ...value})}/><TagPicker
                enterpriseId={enterpriseId} selected={state.draft.tagIds}
                onChange={(value) => changeDraft("tagIds", value)} known={state.detail?.resource.tags}
                canManage={permissions.includes("tag.manage")} readOnly={!editable}/></Section>}
          </Fieldset>
          {kind === "workflow" &&
            <ResourceConfigForm enterpriseId={enterpriseId} kind={kind} config={state.draft.config}
                                onChange={(value) => changeDraft("config", value)} permissions={permissions}
                                errors={errors} readOnly={!editable} resource={state.detail?.resource} busy={busy}/>}
          {kind === "plugin" && section === "connection" && remoteSource(state.draft.config as PluginConfig) && (state.detail ?
            <PluginToolsPanel enterpriseId={enterpriseId} detail={state.detail}
                              value={state.draft.config as PluginConfig}
                              onChange={(value) => changeDraft("config", value)} dirty={dirty || action.busy}
                              onChecked={changed} onChecking={setChecking} readOnly={!editable || action.busy}/> :
            <p className={ui.description}>{uiText("保存连接信息后可读取并选择远程工具。")}</p>)}
          <MutationFeedback action={action} onReload={loadLatest}/>
          {!action.error && Object.keys(errors).length > 0 &&
            <ErrorFeedback error={uiText("配置中有需要修改的内容。")} fieldErrors={errors}/>}
          {editable && dirty && <footer className={styles.editorActions}><span
            className={styles.unsaved}>{uiText("有未保存的修改")}</span><Button className={ui.button} type="button"
                                                                                disabled={busy}
                                                                                onClick={() => setDiscarding(true)}>{uiText("取消修改")}</Button>
          </footer>}
        </form>
        {kind === "knowledge" && section === "documents" && (state.detail ?
          <KnowledgeDocumentsPanel enterpriseId={enterpriseId} resourceId={state.detail.resource.id}
                                   editable={editable && state.detail.resource.status === "active"}
                                   searchable={permissions.includes("knowledge.search") && state.detail.resource.status === "active"}
                                   dirty={dirty || action.busy}/> :
          <p className={ui.description}>{uiText("请先保存知识库，再添加文档。")}</p>)}
        {kind === "data" && section === "collections" && (state.detail ?
          <DataCollectionsPanel key={`${state.detail.resource.id}:${state.detail.resource.revision}`}
                                enterpriseId={enterpriseId} detail={state.detail}
                                editable={editable && state.detail.resource.status === "active"}
                                queryable={permissions.includes("data.query") && state.detail.resource.status === "active"}
                                dirty={dirty || action.busy} onChecked={changed}/> :
          <p className={ui.description}>{uiText("请先保存数据源，再添加集合。")}</p>)}
      </MotionPanel>
      {kind === "agent" && <AgentIntroductionPreview draft={state.draft}/>}
    </div>
    {history && state.detail &&
      <Dialog title={uiText("发布历史")} drawer onClose={() => setHistory(false)}><ResourceVersions
        key={state.detail.resource.revision} enterpriseId={enterpriseId} resource={state.detail.resource}
        permissions={permissions} onLoaded={(detail) => {
        apply(detail);
        setHistory(false);
      }} onChanged={() => void changed()}/></Dialog>}
    {latest && <Dialog title={uiText("查看服务端最新内容")} onClose={() => setLatest(null)}>
      <div className={ui.form}>
        <p
          className={ui.description}>{uiText("其他操作已修改此")}{label}{uiText("。你可以保留自己的修改；没有修改的内容会更新为最新值。当前输入尚未提交。")}</p>
        <p><strong>{latest.resource.name}</strong></p><p className={ui.description}>{latest.resource.description}</p>
        <Disclosure><DisclosureSummary>{uiText("查看配置")}</DisclosureSummary><Fieldset className={styles.editorFields}
                                                                                         disabled={kind !== "workflow"}><ResourceConfigForm
          enterpriseId={enterpriseId} kind={kind} config={latest.draft} onChange={() => {
        }} permissions={permissions} errors={{}} readOnly/></Fieldset></Disclosure>
        <DialogActions className={ui.footer}><DialogAction className={ui.button} onAction={(close) => close(() => {
          const saved = draftFrom(latest);
          setState((previous) => ({
            detail: latest,
            saved,
            draft: mergeDraftChanges(previous.saved, previous.draft, saved)
          }));
          setLatest(null);
          action.resetFeedback();
        })}>{uiText("保留我的修改")}</DialogAction><DialogAction className={ui.primary}
                                                                 onAction={(close) => close(() => {
                                                                   apply(latest);
                                                                   setLatest(null);
                                                                 })}>{uiText("使用服务端内容")}</DialogAction></DialogActions>
      </div>
    </Dialog>}
    {discarding &&
      <Dialog variant="discard" title={uiText("放弃未保存的修改？")} onClose={() => setDiscarding(false)}><DialogActions
        className={ui.footer}><DialogCancel className={ui.button}>{uiText("继续编辑")}</DialogCancel><DialogAction
        className={ui.danger} onAction={(close) => close(() => {
        setState((previous) => ({...previous, draft: previous.saved}));
        action.resetFeedback();
        setDiscarding(false);
      })}>{uiText("放弃修改")}</DialogAction></DialogActions></Dialog>}
  </div>;
}
