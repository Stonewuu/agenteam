"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import type {Environment} from "../types/environment";

export function useEnvironment(enterpriseId: string) {
  const base = `/api/v1/enterprises/${encodeURIComponent(enterpriseId)}/environment`;
  const [environment, setEnvironment] = useState<Environment | null>(null);
  const [connected, setConnected] = useState(false);
  const [pending, setPending] = useState("");
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false);
  const lastActivity = useRef(0);
  const version = useRef(0);
  const inFlight = useRef<{ task: Promise<void>; signal?: AbortSignal } | null>(null);

  const reload = useCallback(async (signal?: AbortSignal) => {
    if (inFlight.current && !inFlight.current.signal?.aborted) {
      return inFlight.current.task;
    }
    const current = version.current;
    const entry: { task: Promise<void>; signal?: AbortSignal } = {task: Promise.resolve(), signal};
    const clear = () => {
      if (inFlight.current === entry) {
        inFlight.current = null;
      }
    };
    signal?.addEventListener("abort", clear, {once: true});
    const runTask = async () => {
      try {
        const state = await apiRequest<Environment>(base, {signal});
        if (!signal?.aborted && current === version.current) {
          setEnvironment(state);
          setConnected(value => value && state.status === "running");
          setError("");
          setLoaded(true);
        }
      } catch (failure) {
        if (!signal?.aborted) {
          setError(errorMessage(failure));
          setLoaded(true);
        }
      }
    };
    entry.task = runTask().finally(() => {
      signal?.removeEventListener("abort", clear);
      clear();
    });
    inFlight.current = entry;
    return entry.task;
  }, [base]);

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      await reload(controller.signal);
      if (!controller.signal.aborted) {
        timer = setTimeout(() => void poll(), 15000);
      }
    };
    // 推迟到提交后启动首次读取，避免严格模式重复挂载立刻中止请求。
    timer = setTimeout(() => void poll(), 0);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [reload]);

  const operate = async (action: "connect" | "stop") => {
    version.current++;
    setPending(action);
    setError("");
    try {
      const state = await apiRequest<Environment>(base + "/" + action, {method: "POST"});
      setEnvironment(state);
      setConnected(action === "connect" && state.status === "running");
      return true;
    } catch (failure) {
      setError(errorMessage(failure));
      return false;
    } finally {
      setPending("");
    }
  };

  const activity = useCallback(() => {
    if (!connected || Date.now() - lastActivity.current < 15000) {
      return;
    }
    lastActivity.current = Date.now();
    void apiRequest(base + "/activity", {method: "POST"}).catch(failure => setError(errorMessage(failure)));
  }, [base, connected]);

  return {base, environment, connected, pending, error, loaded, reload, activity,
    connect: () => operate("connect"), stop: () => operate("stop"), disconnect: () => setConnected(false)};
}
