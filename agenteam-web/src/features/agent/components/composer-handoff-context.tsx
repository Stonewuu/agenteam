"use client";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState} from "react";
import {createComposerNavigationTransition} from "../lib/composer-navigation-transition";
import {prepareComposerHandoff, type ComposerHandoff, type ComposerHandoffInput} from "../lib/composer-handoff";
import type {ConversationSubmission} from "../lib/conversation-submissions";

type HandoffContext = {
  value: ComposerHandoff | null;
  prepare: (input: ComposerHandoffInput) => ComposerHandoff;
  update: (submission: ConversationSubmission, error?: string) => void;
  finish: (conversationId: string) => void;
  discard: (submissionId: string) => void;
  navigate: (action: () => void) => Promise<void>; arrived: () => void; afterTransition: () => Promise<void>
};
const Context = createContext<HandoffContext | null>(null);

/** 跨页面保存首次发送的内容与结果，显示信息不能代替接口权限和会话状态。 */
export function ComposerHandoffProvider({children}: { children: ReactNode }) {
  const [value, setValue] = useState<ComposerHandoff | null>(null);
  const [transition] = useState(createComposerNavigationTransition);
  useEffect(() => () => transition.dispose(), [transition]);
  const prepare = useCallback((input: ComposerHandoffInput) => {
    const next = prepareComposerHandoff(input);
    setValue(next);
    return next;
  }, []);
  const update = useCallback((submission: ConversationSubmission, error = "") => setValue(current =>
    current?.submission.id === submission.id ? {...current, submission, error,
      conversationId: submission.accepted?.conversationId ?? null,
      status: submission.failed ? "failed" : submission.accepted ? "accepted" : "sending"} : current), []);
  const finish = useCallback((id: string) => setValue(current => current?.conversationId === id ? null : current), []);
  const discard = useCallback((id: string) => setValue(current => current?.submission.id === id ? null : current), []);
  const context = useMemo(() => ({
    value,
    prepare,
    update,
    finish,
    discard,
    navigate: transition.navigate,
    arrived: transition.arrived,
    afterTransition: transition.finished
  }), [value, prepare, update, finish, discard, transition]);
  return <Context value={context}>{children}</Context>;
}

export function useComposerHandoff() {
  return useContext(Context);
}
