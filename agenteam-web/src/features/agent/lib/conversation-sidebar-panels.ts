import type {ConversationSidebarTab} from "../types/conversation-sidebar";

export type SidebarPanels = {
  tabs: ConversationSidebarTab[];
  states: Record<string, unknown>;
  origins: Record<string, string>;
};

export function emptySidebarPanels(): SidebarPanels {
  return {tabs: [], states: {}, origins: {}};
}

export function openSidebarPanel(current: SidebarPanels, tab: ConversationSidebarTab, origin: string): SidebarPanels {
  if (current.tabs.some((item) => item.id === tab.id)) {
    return current;
  }
  return {
    tabs: [...current.tabs, tab],
    states: {...current.states, [tab.id]: tab.initialState},
    origins: {...current.origins, [tab.id]: origin}
  };
}

export function closeSidebarPanel(current: SidebarPanels, id: string, selected: string, fixedIds: string[]) {
  if (!current.tabs.some((item) => item.id === id && item.closable)) {
    return {panels: current, selected};
  }
  const tabs = current.tabs.filter((item) => item.id !== id);
  const states = {...current.states};
  const origins = {...current.origins};
  delete states[id];
  delete origins[id];
  const available = [...fixedIds, ...tabs.map((item) => item.id)];
  const fallback = available.includes(current.origins[id]) ? current.origins[id] : available.at(-1) ?? "";
  return {panels: {tabs, states, origins}, selected: selected === id ? fallback : selected};
}
