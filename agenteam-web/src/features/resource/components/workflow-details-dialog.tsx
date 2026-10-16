"use client";

import {useId} from "react";
import {Button} from "@/components/ui/button";
import {Dialog} from "@/components/ui/dialog";
import {Fieldset} from "@/components/ui/fieldset";
import {useT} from "@/lib/i18n/locale-provider";
import type {DraftWrite, ResourceDetail} from "../types/resource";
import {AppearanceFields} from "./appearance-fields";
import {TextField, type FieldErrors} from "./resource-fields";
import {TagPicker} from "./tag-picker";
import ui from "@/components/ui/surface.module.css";

export function WorkflowDetailsDialog({enterpriseId, draft, detail, errors, editable, busy, permissions, onChange, onClose}: {
  enterpriseId: string;
  draft: DraftWrite;
  detail: ResourceDetail | null;
  errors: FieldErrors;
  editable: boolean;
  busy: boolean;
  permissions: string[];
  onChange: <K extends keyof DraftWrite>(key: K, value: DraftWrite[K]) => void;
  onClose: () => void;
}) {
  const text = useT();
  const formId = useId();
  return <Dialog title={text("工作流信息")} drawer onClose={onClose} busy={busy}
    footer={<Button type="submit" form={formId} className={ui.primary} disabled={busy}>{text("完成")}</Button>}>
    <form id={formId} onSubmit={(event) => {
      event.preventDefault();
      onClose();
    }}>
      <Fieldset className={ui.form} disabled={!editable || busy}>
        <TextField label={text("工作流名称")} name="name" value={draft.name}
          onChange={(value) => onChange("name", value)} maximum={80} required errors={errors}/>
        <TextField label={text("简介")} name="description" value={draft.description}
          onChange={(value) => onChange("description", value)} maximum={500} multiline errors={errors}/>
        <AppearanceFields disabled={!editable || busy} value={draft.config}
          onChange={(value) => onChange("config", {...draft.config, ...value})}/>
        <TagPicker enterpriseId={enterpriseId} selected={draft.tagIds} onChange={(value) => onChange("tagIds", value)}
          known={detail?.resource.tags} canManage={permissions.includes("tag.manage")} readOnly={!editable}/>
      </Fieldset>
    </form>
  </Dialog>;
}
