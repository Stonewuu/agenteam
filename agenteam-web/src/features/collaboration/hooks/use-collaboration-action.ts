"use client";

import {useRef} from "react";
import {useFormAction} from "@/features/auth/hooks/use-form-action";

/** 网络结果未确认时保留同一业务请求编号，避免重复追加要求或取消工作。 */
export function useCollaborationAction() {
  const action = useFormAction();
  const pending = useRef<{key: string; id: string} | null>(null);
  const submit = async (path: string, body: Record<string, unknown>) => {
    const key = JSON.stringify([path, body]);
    if (!pending.current || pending.current.key !== key) {
      pending.current = {key, id: crypto.randomUUID()};
    }
    await action.mutation.run(path, {method: "POST", body: {...body, requestId: pending.current.id}});
    pending.current = null;
  };
  return {...action, submit};
}
