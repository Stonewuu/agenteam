"use client";

import {toast as manager, Toaster as ShadcnToaster} from "./shadcn/toast";

export function Toaster() {
  return <ShadcnToaster timeout={4000} limit={3}/>;
}

type ToastOptions = { description?: string; actionProps?: { children: string; onClick: () => void } };

function show(type: "success" | "error" | "info" | "warning", title?: string, options?: ToastOptions) {
  if (!title?.trim()) {
    return;
  }
  return manager.add({type, title, ...options, timeout: type === "error" ? 6000 : 4000});
}

export const toast = {
  success: (title?: string, options?: ToastOptions) => show("success", title, options),
  error: (title?: string, options?: ToastOptions) => show("error", title, options),
  info: (title?: string, options?: ToastOptions) => show("info", title, options),
  warning: (title?: string, options?: ToastOptions) => show("warning", title, options),
  dismiss: manager.close,
};
