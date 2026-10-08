"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import ui from "@/components/ui/surface.module.css";
import {useBlockNavigation} from "./navigation-guard";

export function useConfirmClose(dirty: boolean, busy: boolean, onClose: () => void) {
  const uiText = useT();
  useBlockNavigation(dirty, busy);
  const [confirm, setConfirm] = useState(false);
  const dialog = useDialogControl();
  return {
    dialogRef: dialog.ref, finish: dialog.close, close: () => {
      if (busy) {
        return;
      }
      if (dirty) {
        setConfirm(true);
      } else {
        dialog.close(onClose);
      }
    },
    canClose: () => {
      if (busy) {
        return false;
      }
      if (dirty) {
        setConfirm(true);
        return false;
      }
      return true;
    },
    confirmation: confirm && <Dialog variant="discard" title={uiText("放弃未保存的修改？")} size="small"
                                     onClose={() => setConfirm(false)}><DialogActions className={ui.footer}>
      <DialogCancel className={ui.button}>{uiText("继续编辑")}</DialogCancel><DialogCancel className={ui.danger}
                                                                                           onClick={() => dialog.close(onClose)}>{uiText("放弃修改")}</DialogCancel></DialogActions></Dialog>,
  };
}
