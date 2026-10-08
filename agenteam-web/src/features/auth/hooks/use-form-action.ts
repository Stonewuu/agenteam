"use client";

import {useRef, useState} from "react";
import {ApiError, ApiMutation, errorMessage} from "@/lib/http/api-client";
import {toast} from "@/components/ui/toast";
import {fieldErrorMessages} from "@/lib/form-errors";

export function useFormAction() {
  const [mutation] = useState(() => new ApiMutation());
  const running = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [conflict, setConflict] = useState(false);
  const [details, setDetails] = useState<Record<string, unknown>>({});
  const [fieldErrors, setFieldErrors] = useState<Record<string, string[]>>({});
  const [requestId, setRequestId] = useState<string>();

  async function execute(operation: () => Promise<void>, notice = "操作已完成。") {
    if (running.current) {
      return;
    }
    running.current = true;
    setBusy(true);
    setError("");
    setConflict(false);
    setDetails({});
    setFieldErrors({});
    setRequestId(undefined);
    try {
      await operation();
      toast.success(notice);
    } catch (error) {
      if (!(error instanceof ApiError)) {
        console.error("提交操作失败", {path: window.location.pathname}, error);
      }
      const text = errorMessage(error);
      const hasConflict = error instanceof ApiError && ["VERSION_CONFLICT", "DATA_GENERATION_CHANGED", "DATA_SOURCE_CHANGED", "DATA_FIELD_UNAVAILABLE"].includes(error.code);
      const hasFieldErrors = error instanceof ApiError && Object.keys(error.fieldErrors).length > 0;
      const needsExplanation = error instanceof ApiError && Boolean(error.details.counts);
      if (hasConflict || hasFieldErrors || needsExplanation) {
        setError(text);
      } else {
        toast.error(text);
      }
      setConflict(hasConflict);
      setDetails(error instanceof ApiError ? error.details : {});
      setFieldErrors(error instanceof ApiError ? error.fieldErrors : {});
      setRequestId(error instanceof ApiError ? error.requestId : undefined);
    } finally {
      running.current = false;
      setBusy(false);
    }
  }

  function clearFieldError(field: string) {
    if (!fieldErrors[field]) {
      return;
    }
    const remaining = {...fieldErrors};
    delete remaining[field];
    setFieldErrors(remaining);
    if (!conflict) {
      setError(fieldErrorMessages(remaining).join("\n"));
    }
  }

  return {
    mutation,
    busy,
    error,
    conflict,
    details,
    fieldErrors,
    requestId,
    execute,
    setError,
    clearFieldError,
    resetFeedback: () => {
      setError("");
      setConflict(false);
      setDetails({});
      setFieldErrors({});
      setRequestId(undefined);
    }
  };
}
