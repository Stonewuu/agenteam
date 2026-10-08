"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/field";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {IconCheck, IconFolder, IconPlus} from "@/components/ui/icons";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {useConversationProject} from "../hooks/use-conversation-project";
import type {WorkspaceProject} from "../types/project";
import styles from "./project-picker.module.css";
import ui from "@/components/ui/surface.module.css";

export function ProjectPickerDialog({state, onClose}: {
  state: ReturnType<typeof useConversationProject>;
  onClose: () => void
}) {
  const uiText = useT();
  const dialog = useDialogControl();
  const action = useFormAction();
  const [creating, setCreating] = useState(false);
  const [query, setQuery] = useState("");
  const [name, setName] = useState("");
  const [directory, setDirectory] = useState("");
  const [created, setCreated] = useState<WorkspaceProject | null>(null);
  const path = organizationPath(state.enterprise, "/projects");
  const page = useApiPage<WorkspaceProject>(`${path}?${new URLSearchParams({query})}`, 0, 180, 20, !creating);
  const busy = action.busy || state.saving;

  async function choose(project: WorkspaceProject | null) {
    if (project?.id === state.selected?.id || await state.change(project)) {
      dialog.close();
    }
  }

  async function create() {
    await action.execute(async () => {
      const project = created ?? await action.mutation.run<WorkspaceProject>(path, {
        method: "POST", body: {name: name.trim(), directory: directory.trim() || null},
      });
      setCreated(project);
      await choose(project);
    }, "");
  }

  return <Dialog title={creating ? uiText("创建项目") : uiText("选择项目")} size="medium" busy={busy} onClose={onClose}
                 dialogRef={dialog.ref}>
    <AnimatedHeight preserveControlShadows>{creating ? <DialogForm className={styles.form} onSubmit={(event) => {
      event.preventDefault();
      void create();
    }}>
      <Field label={uiText("项目名称")} required error={action.fieldErrors.name?.join(" ")}>
        <Input name="name" autoFocus autoComplete="off" required maxLength={100} value={created?.name ?? name}
               disabled={busy || Boolean(created)} onChange={(event) => {
          setName(event.target.value);
          action.clearFieldError("name");
        }}/>
      </Field>
      <Field label={uiText("项目目录")} hint={uiText("可填写相对目录，如 reports/quarterly；留空即可。")}
             error={action.fieldErrors.directory?.join(" ")}>
        <Input name="directory" placeholder={uiText("例如 reports/quarterly")} autoComplete="off" maxLength={256}
               value={created?.directory ?? directory} disabled={busy || Boolean(created)} onChange={(event) => {
          setDirectory(event.target.value);
          action.clearFieldError("directory");
        }}/>
      </Field>
      {state.error && <p className={styles.error} role="alert">{localizeUiMessage(state.error ?? "", uiText)}</p>}
      <MutationFeedback action={action}/>
      <DialogActions><Button type="button" className={ui.button} disabled={busy}
                             onClick={() => setCreating(false)}>{uiText("返回列表")}</Button>
        <Button type="submit" className={ui.primary}
                disabled={busy || state.disabled}>{busy ? uiText("正在保存…") : created ? uiText("使用此项目") : uiText("创建并使用")}</Button></DialogActions>
    </DialogForm> : <div className={styles.browser}>
      <div className={styles.search}><Input aria-label={uiText("搜索项目")} placeholder={uiText("搜索项目")}
                                            value={query} disabled={busy}
                                            onChange={(event) => setQuery(event.target.value)}/>
        <Button type="button" className={ui.button} disabled={busy || state.disabled} onClick={() => setCreating(true)}><IconPlus
          size={16}/>{uiText("创建项目")}</Button></div>
      {!state.existing &&
        <Button type="button" className={styles.project} aria-pressed={!state.selected} disabled={busy}
                onClick={() => void choose(null)}>
          <IconPlus size={19}/><span><strong>{uiText("新项目")}</strong></span>{!state.selected &&
          <IconCheck size={16}/>}
        </Button>}
      <div className={styles.list} aria-label={uiText("已有项目")} aria-busy={page.loading}>
        {page.loading && !page.data ?
          <p className={styles.notice} role="status">{uiText("正在加载项目…")}</p> : page.error ?
            <p className={styles.error} role="alert">{localizeUiMessage(page.error ?? "", uiText)}<Button type="button"
                                                                                                          className={styles.textButton}
                                                                                                          onClick={page.retry}>{uiText("重新加载")}</Button>
            </p>
            : page.data?.items.length ? page.data.items.map((project) => <Button key={project.id} type="button"
                                                                                 className={styles.project}
                                                                                 aria-pressed={state.selected?.id === project.id}
                                                                                 disabled={busy || state.disabled || page.loading}
                                                                                 onClick={() => void choose(project)}>
                <IconFolder
                  size={19}/><span><strong>{project.name}</strong><small>{project.directory}</small></span>{state.selected?.id === project.id &&
                <IconCheck size={16}/>}
              </Button>) :
              <p className={styles.notice}>{query ? uiText("没有找到匹配的项目。") : uiText("还没有项目。")}</p>}
      </div>
      {(page.previous.length > 0 || page.data?.hasMore) && <div className={styles.pagination}>
        <Button type="button" className={ui.button} disabled={busy || page.loading || !page.previous.length}
                onClick={page.back}>{uiText("上一页")}</Button>
        <Button type="button" className={ui.button} disabled={busy || page.loading || !page.data?.hasMore}
                onClick={page.next}>{uiText("下一页")}</Button>
      </div>}
      {state.error && <p className={styles.error} role="alert">{localizeUiMessage(state.error ?? "", uiText)}</p>}
      <DialogActions><DialogCancel className={ui.button} disabled={busy}>{uiText("取消")}</DialogCancel></DialogActions>
    </div>}</AnimatedHeight>
  </Dialog>;
}
