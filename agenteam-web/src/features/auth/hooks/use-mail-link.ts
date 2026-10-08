"use client";


import {useEffect, useRef, useState} from "react";
import {bootstrapStatus} from "../api/identity-api";
import {errorMessage} from "@/lib/http/api-client";

/** 邮件凭据只保留在当前页面内存，读取后从地址栏移除，不写入浏览器存储。 */
export function useMailLink() {
  const original = useRef<string | null | undefined>(undefined);
  const version = useRef(0);
  const [state, setState] = useState<{
    loaded: boolean;
    token: string | null;
    error: string;
    version: number
  }>({loaded: false, token: null, error: "", version: 0});
  useEffect(() => {
    let controller: AbortController | null = null;

    async function load() {
      controller?.abort();
      const current = new AbortController();
      controller = current;
      const fragment = new URLSearchParams(window.location.hash.slice(1));
      if (original.current === undefined || fragment.has("token")) {
        original.current = fragment.get("token");
      }
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
      const token = original.current;
      const currentVersion = ++version.current;
      setState({loaded: false, token, error: "", version: currentVersion});
      try {
        const status = await bootstrapStatus(current.signal);
        if (current.signal.aborted) {
          return;
        }
        if (!status.initialized) {
          throw new Error("系统尚未准备好，请联系管理员。");
        }
        if (token !== null && !/^[A-Za-z0-9_-]{43}$/.test(token)) {
          throw new Error("链接内容不完整，请重新打开邮件中的链接。");
        }
        setState({loaded: true, token, error: "", version: currentVersion});
      } catch (error) {
        if (!current.signal.aborted) {
          setState({loaded: true, token, error: errorMessage(error), version: currentVersion});
        }
      }
    }

    void load();
    const changed = () => {
      if (window.location.hash) {
        void load();
      }
    };
    window.addEventListener("hashchange", changed);
    return () => {
      controller?.abort();
      window.removeEventListener("hashchange", changed);
    };
  }, []);
  return state;
}
