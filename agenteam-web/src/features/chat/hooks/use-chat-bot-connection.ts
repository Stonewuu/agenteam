"use client";

import {useRef, useState} from "react";
import {ApiError, apiRequest, type ApiMutation} from "@/lib/http/api-client";
import {useApiPage} from "@/lib/http/use-api-query";
import {integrationPath, type Integration} from "@/features/integration/types/integration";
import type {ChatBot} from "../types/chat";

/** 保存每一步的实际接入记录，校验或绑定失败后继续同一条配置。 */
export function useChatBotConnection({enterpriseId, system, initial, connection, canTest}: {
  enterpriseId: string; system: boolean; initial: ChatBot | null; connection?: Integration; canTest: boolean;
}) {
  const path = integrationPath(enterpriseId, system);
  const [source, setSource] = useState(connection || !canTest ? "existing" : "new");
  const [provider, setProvider] = useState(initial?.providerCode ?? connection?.providerCode ?? "feishu");
  const [connectionId, setConnectionId] = useState(initial?.connectionId ?? connection?.id ?? "");
  const [appId, setAppId] = useState(connection?.externalAppId ?? "");
  const [secret, setSecret] = useState("");
  const [saved, setSaved] = useState<Integration | null>(connection ?? null);
  const savedRef = useRef<Integration | null>(connection ?? null);
  const apps = useApiPage<Integration>(`${path}?providerCode=${encodeURIComponent(provider)}&unboundBotOnly=true`, 0, 0, 30, !initial && source === "existing");
  const remember = (value: Integration) => {
    savedRef.current = value;
    setSaved(value);
    setConnectionId(value.id);
    return value;
  };
  async function ensure(mutation: ApiMutation, roleName: string) {
    if (initial) {
      return initial.connectionId;
    }
    let current = savedRef.current;
    if (!current && source === "existing") {
      if (!connectionId) {
        throw new ApiError(400, "VALIDATION_ERROR", "请选择机器人使用的应用。", {connectionId: ["请选择机器人使用的应用。"]});
      }
      current = remember(await apiRequest<Integration>(`${path}/${encodeURIComponent(connectionId)}`));
    }
    if (!current) {
      current = remember(await mutation.run<Integration>(path, {method: "POST", body: {
        purpose: "chat_bot", providerCode: provider, name: roleName, externalAppId: appId, secret,
        bindingEnabled: provider === "feishu", loginEnabled: false, messagingEnabled: false, configuration: {},
      }}));
      setSecret("");
    } else {
      current = remember(await apiRequest<Integration>(`${path}/${encodeURIComponent(current.id)}`));
      if (secret.trim()) {
        current = remember(await mutation.run<Integration>(`${path}/${current.id}/rotate-secret`, {method: "POST", revision: current.revision, body: {secret}}));
        setSecret("");
      }
    }
    if (current.purpose === "enterprise" && current.status !== "enabled") {
      throw new ApiError(409, "INTEGRATION_CHECK_REQUIRED", "请先在企业接入中启用此应用，或选择新建机器人。");
    }
    if (current.status !== "enabled") {
      if (current.lastCheckStatus !== "passed") {
        if (!canTest) {
          throw new ApiError(403, "INTEGRATION_CHECK_REQUIRED", "当前账号不能校验应用，请选择已启用的应用。");
        }
        current = remember(await mutation.run<Integration>(`${path}/${current.id}/check`, {method: "POST", revision: current.revision}));
        if (current.lastCheckStatus !== "passed") {
          const reason = current.lastCheckError || "应用校验未通过，请核对机器人编号和密钥。";
          throw new ApiError(400, "INTEGRATION_CHECK_FAILED", reason, {secret: [reason]});
        }
      }
      current = remember(await mutation.run<Integration>(`${path}/${current.id}/status`, {method: "PATCH", revision: current.revision, body: {status: "enabled"}}));
    }
    return current.id;
  }
  return {source, setSource, provider, setProvider, connectionId, setConnectionId, appId, setAppId, secret, setSecret, saved, apps, ensure};
}
