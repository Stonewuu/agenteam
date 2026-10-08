"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import type {useFormAction} from "@/features/auth/hooks/use-form-action";
import {ErrorFeedback} from "@/components/ui/error-feedback";
import styles from "@/components/ui/surface.module.css";

export function MutationFeedback({action, onReload, showFieldErrors = true}: {
  action: ReturnType<typeof useFormAction>;
  onReload?: () => void;
  showFieldErrors?: boolean
}) {
  const uiText = useT();
  return <>
    <ErrorFeedback error={action.error} fieldErrors={showFieldErrors ? action.fieldErrors : {}}
                   requestId={action.requestId} onFieldChange={action.clearFieldError}/>
    {action.conflict && onReload && <Button type="button" className={styles.button} disabled={action.busy}
                                            onClick={onReload}>{uiText("重新加载最新内容")}</Button>}
  </>;
}
