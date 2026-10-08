"use client";

import {useState} from "react";
import type {ConversationSidebarTab} from "../types/conversation-sidebar";
import {closeSidebarPanel, emptySidebarPanels, openSidebarPanel} from "../lib/conversation-sidebar-panels";

export function useSidebarPanels(scope: string, fixedTabs: readonly ConversationSidebarTab[], tab: string, onTabChange: (id: string) => void) {
  const [saved, setSaved] = useState(() => ({scope, panels: emptySidebarPanels()}));
  const current = saved.scope === scope ? saved.panels : emptySidebarPanels();
  if (saved.scope !== scope) {
    setSaved({scope, panels: current});
  }
  const tabs = [...fixedTabs, ...current.tabs];
  const selected = tabs.some((item) => item.id === tab) ? tab : fixedTabs[0]?.id ?? "";
  const open = (next: ConversationSidebarTab) => {
    if (!fixedTabs.some((item) => item.id === next.id)) {
      setSaved((previous) => ({
        scope,
        panels: openSidebarPanel(previous.scope === scope ? previous.panels : emptySidebarPanels(), next, selected)
      }));
    }
    onTabChange(next.id);
  };
  const close = (id: string) => {
    const result = closeSidebarPanel(current, id, selected, fixedTabs.map((item) => item.id));
    setSaved({scope, panels: result.panels});
    if (result.selected !== selected) {
      onTabChange(result.selected);
    }
    return result.selected;
  };
  const update = (id: string, state: unknown) => {
    setSaved((previous) => {
      const panels = previous.scope === scope ? previous.panels : emptySidebarPanels();
      return {scope, panels: {...panels, states: {...panels.states, [id]: state}}};
    });
  };
  return {tabs, selected, states: current.states, open, close, update};
}
