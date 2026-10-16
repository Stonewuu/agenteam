"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState} from "react";
import {useRouter} from "next/navigation";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import ui from "@/components/ui/surface.module.css";

type EditState = { dirty: boolean; busy: boolean; scope?: string };
type Guard = { register: (key: symbol, state: EditState | null) => void; request: (action: () => void, scope?: string) => void };
const NavigationContext = createContext<Guard | null>(null);
type HistoryNavigation = EventTarget & { traverseTo: (key: string) => { finished: Promise<unknown> } };
type HistoryNavigateEvent = Event & { navigationType: string; destination: { key: string; url: string } };

export function NavigationGuard({children}: { children: ReactNode }) {
  const uiText = useT();
  const router = useRouter();
  const editors = useRef(new Map<symbol, EditState>());
  const [pending, setPending] = useState<{action: () => void; scope?: string} | null>(null);
  const [busyNotice, setBusyNotice] = useState(false);
  const register = useCallback((key: symbol, state: EditState | null) => {
    if (state) {
      editors.current.set(key, state);
    } else {
      editors.current.delete(key);
    }
  }, []);
  const request = useCallback((action: () => void, scope?: string) => {
    const states = [...editors.current.values()].filter((state) => scope === undefined || state.scope === scope);
    if (states.some((value) => value.busy)) {
      setBusyNotice(true);
    } else if (states.some((value) => value.dirty)) {
      setPending({action, scope});
    } else {
      action();
    }
  }, []);
  useEffect(() => {
    const leave = (event: BeforeUnloadEvent) => {
      if ([...editors.current.values()].some((value) => value.dirty || value.busy)) {
        event.preventDefault();
      }
    };
    const click = (event: MouseEvent) => {
      const link = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>("a[href]") : null;
      if (!link || event.button !== 0 || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey || link.target === "_blank" || link.hasAttribute("download")) {
        return;
      }
      const target = new URL(link.href);
      // 同一页的正文定位不会卸载表单，不应触发离开确认或阻止正文获得焦点。
      if (target.hash && target.origin === window.location.origin && target.pathname === window.location.pathname
        && target.search === window.location.search) {
        return;
      }
      if (![...editors.current.values()].some((value) => value.dirty || value.busy) || link.href === window.location.href) {
        return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      request(() => {
        if (target.origin === window.location.origin) {
          router.push(target.pathname + target.search + target.hash);
        } else {
          window.location.assign(target.href);
        }
      });
    };
    // 浏览器允许取消的历史导航必须在地址改变之前拦截；popstate 发生时路由可能已经卸载表单。
    const navigation = (window as Window & { navigation?: HistoryNavigation }).navigation;
    const navigate = (value: Event) => {
      const event = value as HistoryNavigateEvent;
      // 设置页的分类共用已挂载表单，历史切换不会丢失输入。
      const destination = new URL(event.destination.url, window.location.origin);
      if (window.location.pathname === "/settings" && destination.origin === window.location.origin && destination.pathname === "/settings") {
        return;
      }
      if (event.navigationType !== "traverse" || !event.cancelable || ![...editors.current.values()].some((state) => state.dirty || state.busy)) {
        return;
      }
      event.preventDefault();
      request(() => {
        void navigation?.traverseTo(event.destination.key).finished.catch(() => router.push(event.destination.url));
      });
    };
    window.addEventListener("beforeunload", leave);
    document.addEventListener("click", click, true);
    navigation?.addEventListener("navigate", navigate);
    return () => {
      window.removeEventListener("beforeunload", leave);
      document.removeEventListener("click", click, true);
      navigation?.removeEventListener("navigate", navigate);
    };
  }, [request, router]);
  const context = useMemo(() => ({register, request}), [register, request]);
  return <NavigationContext.Provider value={context}>{children}
    {pending && <Dialog variant="discard" title={uiText("离开并放弃未保存的修改？")}
                        onClose={() => setPending(null)}><DialogActions className={ui.footer}>
      <DialogCancel className={ui.button}>{uiText("继续编辑")}</DialogCancel><DialogAction className={ui.danger}
                                                                                           onAction={(close) => close(() => {
                                                                                             const action = pending.action;
                                                                                             for (const [key, state] of editors.current) {
                                                                                               if (pending.scope === undefined || state.scope === pending.scope) {
                                                                                                 editors.current.delete(key);
                                                                                               }
                                                                                             }
                                                                                             setPending(null);
                                                                                             action();
                                                                                           })}>{uiText("放弃修改")}</DialogAction></DialogActions></Dialog>}
    {busyNotice && <Dialog title={uiText("正在提交修改")} onClose={() => setBusyNotice(false)}><p
      className={ui.description}>{uiText("请等待当前操作返回结果。")}</p><DialogCancel
      className={ui.button}>{uiText("返回")}</DialogCancel></Dialog>}
  </NavigationContext.Provider>;
}

export function useBlockNavigation(dirty: boolean, busy = false, scope?: string) {
  const uiText = useT();
  const guard = useContext(NavigationContext);
  const [key] = useState(() => Symbol(uiText("编辑表单")));
  useEffect(() => {
    guard?.register(key, {dirty, busy, scope});
    return () => guard?.register(key, null);
  }, [guard, key, dirty, busy, scope]);
}

export function useGuardedNavigation(scope?: string) {
  const guard = useContext(NavigationContext);
  const request = useCallback((action: () => void) => guard?.request(action, scope), [guard, scope]);
  return guard ? request : undefined;
}
