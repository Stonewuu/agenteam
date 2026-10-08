"use client";

import {useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {loadAuthEntry} from "../api/identity-api";
import {requestedDestination} from "../lib/identity-navigation";
import {BootstrapForm} from "./bootstrap-form";
import {LoginForm} from "./login-form";
import {errorMessage} from "@/lib/http/api-client";
import type {IdentityUser} from "../types/identity";
import {WorkspaceLoading} from "@/features/workspace/components/workspace-loading";
import {LoadingTransition} from "@/components/ui/loading-transition";

export function AuthEntry() {
  const router = useRouter();
  const [view, setView] = useState<"loading" | "login" | "setup" | "error">("loading");
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();

    async function load() {
      try {
        const result = await loadAuthEntry(controller.signal);
        if (controller.signal.aborted) {
          return;
        }
        if (result.kind !== "authenticated") {
          setView(result.kind);
          return;
        }
        router.replace(requestedDestination(result.user));
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }
        console.error("读取首页登录信息失败", error);
        setError(errorMessage(error));
        setView("error");
      }
    }

    void load();
    return () => controller.abort();
  }, [router, retry]);

  function openWorkspace(user: IdentityUser) {
    setView("loading");
    router.replace(requestedDestination(user));
  }

  return <LoadingTransition state={view}>{view === "setup" ? <BootstrapForm onSuccess={openWorkspace}/>
    : view === "login" ? <LoginForm onSuccess={openWorkspace}/> :
      <WorkspaceLoading error={view === "error" ? error : undefined} onRetry={() => {
        setView("loading");
        setRetry((value) => value + 1);
      }}/>}</LoadingTransition>;
}
