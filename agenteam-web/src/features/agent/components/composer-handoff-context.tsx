"use client";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState} from "react";
import type {Employee} from "@/features/employee/types/employee";
import type {ConversationModelOptions, ToolApprovalPolicy} from "../types/execution";
import type {WorkspaceProject} from "@/features/project/types/project";
import {createComposerNavigationTransition} from "../lib/composer-navigation-transition";

type ComposerHandoff = {
  conversationId: string;
  employee: Employee;
  approvalPolicy: ToolApprovalPolicy;
  focusInput: boolean;
  modelOptions?: ConversationModelOptions;
  project?: WorkspaceProject | null
};
type HandoffContext = {
  value: ComposerHandoff | null; prepare: (value: ComposerHandoff) => void; finish: (conversationId: string) => void;
  navigate: (action: () => void) => Promise<void>; arrived: () => void; afterTransition: () => Promise<void>
};
const Context = createContext<HandoffContext | null>(null);

/** 仅暂存本次成功发送的真实显示信息，不能代替接口的权限与会话状态。 */
export function ComposerHandoffProvider({children}: { children: ReactNode }) {
  const [value, prepare] = useState<ComposerHandoff | null>(null);
  const [transition] = useState(createComposerNavigationTransition);
  useEffect(() => () => transition.dispose(), [transition]);
  const finish = useCallback((id: string) => prepare((current) => current?.conversationId === id ? null : current), []);
  const context = useMemo(() => ({
    value,
    prepare,
    finish,
    navigate: transition.navigate,
    arrived: transition.arrived,
    afterTransition: transition.finished
  }), [value, finish, transition]);
  return <Context value={context}>{children}</Context>;
}

export function useComposerHandoff() {
  return useContext(Context);
}
