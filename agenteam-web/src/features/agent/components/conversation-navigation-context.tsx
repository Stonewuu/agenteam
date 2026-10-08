"use client";

import {createContext, type ReactNode, useCallback, useContext, useRef, useState, useSyncExternalStore} from "react";
import {usePathname, useRouter} from "next/navigation";
import type {EnterpriseContext} from "@/features/auth/types/identity";
import {useConversationList} from "../hooks/use-conversation-list";
import {useFileSidebarOpen} from "../hooks/use-file-sidebar-open";

type NavigationState = {
  collapsed: boolean; setCollapsed: (value: boolean) => void;
  fileSidebarOpen: boolean; setFileSidebarOpen: (value: boolean) => void;
  fileSidebarTab: string; setFileSidebarTab: (value: string) => void;
  mobile: boolean; mobileListOpen: boolean; setMobileListOpen: (value: boolean) => void;
  list: ReturnType<typeof useConversationList>; context: EnterpriseContext;
  startNewTask: () => void; registerNewTaskAction: (action: () => void) => () => void;
};
const ConversationNavigationContext = createContext<NavigationState | null>(null);
const mobileQuery = "(max-width: 800px)";
const readMobile = () => window.matchMedia(mobileQuery).matches;

function subscribeMobile(callback: () => void) {
  const media = window.matchMedia(mobileQuery);
  media.addEventListener("change", callback);
  return () => media.removeEventListener("change", callback);
}

export function ConversationNavigationProvider({context, children}: {
  context: EnterpriseContext;
  children: ReactNode
}) {
  const [collapsed, setCollapsed] = useState(false);
  const [fileSidebarOpen, setFileSidebarOpen] = useFileSidebarOpen(context.member.userId, context.enterprise.id);
  const [fileSidebarTab, setFileSidebarTab] = useState("files");
  const [mobileListFor, setMobileListFor] = useState<string | null>(null);
  const pathname = usePathname();
  const router = useRouter();
  const newTaskAction = useRef<(() => void) | null>(null);
  const registerNewTaskAction = useCallback((action: () => void) => {
    newTaskAction.current = action;
    return () => {
      if (newTaskAction.current === action) {
        newTaskAction.current = null;
      }
    };
  }, []);
  const startNewTask = useCallback(() => {
    if (newTaskAction.current) {
      newTaskAction.current();
    } else {
      router.push(`/enterprises/${encodeURIComponent(context.enterprise.id)}/new-task`);
    }
  }, [context.enterprise.id, router]);
  const mobile = useSyncExternalStore(subscribeMobile, readMobile, () => false);
  const list = useConversationList(context.enterprise.id, context.permissions.includes("conversation.view"));
  return <ConversationNavigationContext.Provider value={{
    collapsed,
    setCollapsed,
    fileSidebarOpen,
    setFileSidebarOpen,
    fileSidebarTab,
    setFileSidebarTab,
    mobile,
    mobileListOpen: mobileListFor === pathname,
    setMobileListOpen: (value) => setMobileListFor(value ? pathname : null),
    list,
    context,
    startNewTask,
    registerNewTaskAction
  }}>{children}</ConversationNavigationContext.Provider>;
}

export function useConversationNavigation() {
  return useContext(ConversationNavigationContext);
}
