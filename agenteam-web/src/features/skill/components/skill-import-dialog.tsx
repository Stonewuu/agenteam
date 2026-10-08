"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Disclosure, DisclosureSummary} from "@/components/ui/disclosure";

import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";
import {Fieldset} from "@/components/ui/fieldset";

import {useEffect, useRef, useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {ApiMutation, errorMessage} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {uploadFile} from "@/features/file/api/file-api";
import type {FileReference} from "@/features/agent/types/execution";
import type {ResourceDetail, SkillConfig} from "@/features/resource/types/resource";
import {ResourceConfigForm} from "@/features/resource/components/resource-config-form";
import {TextField} from "@/features/resource/components/resource-fields";
import {IconFileText, IconUpload} from "@/components/ui/icons";
import {fileStatusText} from "@/features/file/lib/file-status";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import type {SkillImportPreview} from "../types/skill";
import ui from "@/components/ui/surface.module.css";

export function SkillImportDialog({enterpriseId, permissions, onClose, onImported}: {
  enterpriseId: string;
  permissions: string[];
  onClose: () => void;
  onImported: (value: ResourceDetail) => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const previewMutation = useRef(new ApiMutation());
  const controller = useRef<AbortController | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [fileId, setFileId] = useState<string | null>(null);
  const [preview, setPreview] = useState<SkillImportPreview | null>(null);
  const [readingError, setReadingError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [readAttempt, setReadAttempt] = useState(0);
  const [confirming, setConfirming] = useState(false);
  const [dirty, setDirty] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const closing = useConfirmClose(dirty, action.busy || confirming, onClose);
  const state = useApiQuery<FileReference>(fileId ? organizationPath(enterpriseId, `/files/${encodeURIComponent(fileId)}`) : null, refresh);
  const fileStatus = state.data?.status;
  useEffect(() => () => controller.current?.abort(), []);
  useEffect(() => {
    if (!fileId || preview || fileStatus === "rejected" || fileStatus === "deleted") {
      return;
    }
    const timer = window.setInterval(() => setRefresh((value) => value + 1), 2000);
    return () => window.clearInterval(timer);
  }, [fileId, preview, fileStatus]);
  useEffect(() => {
    if (!fileId || fileStatus !== "ready") {
      return;
    }
    const request = new AbortController();
    previewMutation.current.run<SkillImportPreview>(organizationPath(enterpriseId, "/skills/import-preview"), {
      method: "POST",
      body: {fileId},
      signal: request.signal
    })
      .then((value) => {
        if (!request.signal.aborted) {
          setPreview(value);
          setReadingError("");
        }
      })
      .catch((error) => {
        if (!request.signal.aborted) {
          console.error("读取技能导入预览失败", {fileId}, error);
          setReadingError(errorMessage(error));
        }
      });
    return () => request.abort();
  }, [enterpriseId, fileId, fileStatus, readAttempt]);
  return <><Dialog title={uiText("导入技能")} onClose={onClose} dialogRef={closing.dialogRef}
                   onRequestClose={closing.canClose} busy={action.busy || confirming}>
    {preview ? <ConfirmImport enterpriseId={enterpriseId} permissions={permissions} preview={preview}
                              onImported={(value) => closing.finish(() => onImported(value))} onBusy={setConfirming}
                              onDirty={setDirty}/> : <div className={ui.form}>
      <Input ref={input} hidden type="file" accept=".json,.md,.txt" disabled={action.busy} onChange={(event) => {
        controller.current?.abort();
        setFile(event.target.files?.[0] ?? null);
        setFileId(null);
        setPreview(null);
        setReadingError("");
        action.resetFeedback();
      }}/>
      <Button type="button" className="upload-area" disabled={action.busy}
              onClick={() => input.current?.click()}><IconUpload
        size={26}/><strong>{file?.name ?? uiText("选择技能文件")}</strong><span>{file ? uiText("点击重新选择") : uiText("JSON、Markdown 或 TXT")}</span></Button>
      <p className={ui.description}>{uiText("支持一兆字节以内的 JSON、Markdown 和 TXT 文件。")}</p>
      {fileId && <p role="status"
                    className={ui.notice}>{state.error || (fileStatus === "ready" ? uiText("正在读取技能内容…") : state.data ? uiText(fileStatusText(state.data)) : uiText("正在准备文件…"))}</p>}
      {readingError && <div className={ui.feedback}><p className={ui.error}
                                                       role="alert">{localizeUiMessage(readingError ?? "", uiText)}</p>
        <Button className={ui.button} type="button"
                onClick={() => setReadAttempt((value) => value + 1)}>{uiText("重新读取")}</Button></div>}
      <MutationFeedback action={action}/>
      <DialogActions className={ui.footer}><DialogCancel type="button" className={ui.button}
                                                         disabled={action.busy}>{uiText("取消")}</DialogCancel>
        <Button type="button" className={ui.primary}
                disabled={!file || action.busy || fileStatus === "scanning" || fileStatus === "ready"}
                onClick={() => void action.execute(async () => {
                  if (!file) {
                    return;
                  }
                  const request = new AbortController();
                  controller.current = request;
                  await uploadFile({
                    enterpriseId,
                    file,
                    purpose: "skill_import",
                    mutation: action.mutation,
                    signal: request.signal,
                    onPrepared: setFileId,
                    existingId: fileId
                  });
                  setRefresh((value) => value + 1);
                }, "")}>{action.busy ? uiText("正在上传…") : fileId ? uiText("重试上传") : uiText("读取文件")}</Button></DialogActions>
    </div>}
  </Dialog>{closing.confirmation}</>;
}

function ConfirmImport({enterpriseId, permissions, preview, onImported, onBusy, onDirty}: {
  enterpriseId: string;
  permissions: string[];
  preview: SkillImportPreview;
  onImported: (value: ResourceDetail) => void;
  onBusy: (value: boolean) => void;
  onDirty: (value: boolean) => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const [name, setName] = useState(preview.name);
  const [description, setDescription] = useState(preview.description);
  const [config, setConfig] = useState<SkillConfig>(preview.config);
  useEffect(() => {
    onDirty(name !== preview.name || description !== preview.description || config !== preview.config);
  }, [name, description, config, preview, onDirty]);
  return <DialogForm className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    void action.execute(async () => {
      onBusy(true);
      try {
        const result = await action.mutation.run<ResourceDetail>(organizationPath(enterpriseId, "/skills/import"), {
          method: "POST",
          body: {previewToken: preview.previewToken, name, description, config}
        });
        onImported(result);
      } finally {
        onBusy(false);
      }
    }, "");
  }}>
    <Fieldset className={ui.form} style={{border: 0, margin: 0, padding: 0}} disabled={action.busy}>
      <TextField label={uiText("技能名称")} name="name" value={name} onChange={setName} required maximum={80}
                 errors={action.fieldErrors}/>
      <TextField label={uiText("简介")} name="description" value={description} onChange={setDescription} maximum={500}
                 multiline errors={action.fieldErrors}/>
      {preview.unresolvedDependencies.length > 0 &&
        <div className={ui.feedback}><p>{uiText("以下能力需要重新选择：")}</p>
          <ul>{preview.unresolvedDependencies.map((value, index) => <li
            key={index}>{value.name}（{value.kind === "plugin" ? uiText("插件") : uiText("知识库")}）</li>)}</ul>
        </div>}
      <div className="import-instructions"><h3><IconFileText size={18}/>{uiText("指令预览")}</h3>
        <p>{config.instructions}</p></div>
      <Disclosure><DisclosureSummary>{uiText("调整技能内容与依赖")}</DisclosureSummary><ResourceConfigForm
        enterpriseId={enterpriseId} kind="skill" config={config} onChange={(value) => setConfig(value as SkillConfig)}
        permissions={permissions} errors={action.fieldErrors}/></Disclosure>
    </Fieldset><MutationFeedback action={action}/>
    <DialogActions className={ui.footer}><DialogCancel className={ui.button} type="button"
                                                       disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
      className={ui.primary}
      disabled={action.busy || !name.trim()}>{action.busy ? uiText("正在创建…") : uiText("创建草稿")}</Button></DialogActions>
  </DialogForm>;
}
