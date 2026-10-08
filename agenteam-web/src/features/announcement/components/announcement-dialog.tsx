"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {IconBuilding, IconCheck, IconClock, IconShield, IconUser} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {RichTextContent} from "@/components/ui/rich-text-content";
import {type Announcement, announcementTitle} from "../types/announcement";
import styles from "./announcement.module.css";

export function AnnouncementDialog({enterpriseId, value, onClose, onRead}: {
  enterpriseId: string; value: Announcement; onClose: () => void; onRead: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  const Icon = value.scope === "platform" ? IconShield : IconBuilding;
  return <Dialog title={announcementTitle(value, uiText)} onClose={onClose} busy={action.busy} dialogRef={dialog.ref}
                 className={`${styles.dialog} ${styles[value.level.tone] ?? ""}`}
                 headerDetails={<div className={styles.dialogMetadata}>
                   <span className={styles.dialogLevel}><Icon size={17}/><span>{value.level.name}</span></span>
                   {value.publisherName && <span className={styles.publisher}><IconUser
                     size={14}/><span>{uiText("发布人：")}{value.publisherName}</span></span>}
                   {value.publishedAt && <span className={styles.publishedAt}><IconClock size={14}/><EnterpriseDateTime
                     value={value.publishedAt}/></span>}
                 </div>}>
    <div className={styles.article}><RichTextContent value={value}/></div>
    <MutationFeedback action={action} onReload={onRead}/>
    <DialogActions><DialogCancel type="button" className={styles.dismissButton}
                                 disabled={action.busy}>{value.readAt ? uiText("关闭") : uiText("稍后阅读")}</DialogCancel>
      {!value.readAt &&
        <Button className={styles.readButton} disabled={action.busy} onClick={() => void action.execute(async () => {
          await action.mutation.run(organizationPath(enterpriseId, `/announcements/${encodeURIComponent(value.id)}/read`), {
            method: "POST",
            body: {version: value.version}
          });
          dialog.close(onRead);
        }, "")}><IconCheck size={17}/>{action.busy ? uiText("正在标记…") : uiText("已读")}</Button>}
    </DialogActions>
  </Dialog>;
}
