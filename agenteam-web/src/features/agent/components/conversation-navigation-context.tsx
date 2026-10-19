"use client";

import {createContext, type ReactNode, useCallback, useContext, useRef, useState, useSyncExternalStore} from "react";
import {usePathname, useRouter} from "next/navigation";
import type {EnterpriseContext} from "@/features/auth/types/identity";
import {useConversationList} from "../hooks/use-conversation-list";
import {useFileSidebarOpen} from "../hooks/use-file-sidebar-open";
import type {Conversation} from "../types/execution";

type NavigationState = {
  collapsed: boolean; setCollapsed: (value: boolean) => void;
  fileSidebarOpen: boolean; setFileSidebarOpen: (value: boolean) => void;
  fileSidebarTab: string; setFileSidebarTab: (value: string) => void;
  mobile: boolean; mobileListOpen: boolean; setMobileListOpen: (value: boolean) => void;
  list: ReturnType<typeof useConversationList>; context: EnterpriseContext;
  startNewTask: () => void; registerNewTaskAction: (action: () => void) => () => void;
  currentConversation: Conversation | null; setCurrentConversation: (value: Conversation | null) => void;
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
  const [currentConversation, setCurrentConversation] = useState<Conversation | null>(null);
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
      router.push(`/new-task`);
    }
  }, [router]);
  const mobile = useSyncExternalStore(subscribeMobile, readMobile, () => false);
  const list = useConversationList(context.enterprise.id, context.permissions.includes("conversation.view"), context.member.userId);
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
    registerNewTaskAction,
    currentConversation,
    setCurrentConversation
  }}>{children}</ConversationNavigationContext.Provider>;
}

export function useConversationNavigation() {
  return useContext(ConversationNavigationContext);
}
