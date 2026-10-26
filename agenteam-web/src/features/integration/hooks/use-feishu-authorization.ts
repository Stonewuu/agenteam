"use client";

import { useEffect, useRef, useState } from "react";
import { listenForFeishuAuthorization } from "../lib/feishu-authorization-channel";

export function useFeishuAuthorization(onResult: () => void) {
  const pending = useRef<{ connectionId: string; popup: Window | null; stop: () => void } | null>(null);
  const [fallback, setFallback] = useState<{ connectionId: string; url: string } | null>(null);
  useEffect(() => () => pending.current?.stop(), []);

  async function open(connectionId: string, authorize: () => Promise<{ authorizationUrl: string }>) {
    if (pending.current?.connectionId === connectionId && pending.current.popup && !pending.current.popup.closed) {
      pending.current.popup.focus();
      return;
    }
    pending.current?.stop();
    const popup = window.open("", "_blank");
    if (popup) {
      popup.opener = null;
    }
    const stop =
      typeof BroadcastChannel === "undefined" ? () => {} : listenForFeishuAuthorization(connectionId, onResult);
    pending.current = { connectionId, popup, stop };
    setFallback(null);
    try {
      const result = await authorize();
      if (popup && !popup.closed) {
        popup.location.replace(result.authorizationUrl);
      } else {
        setFallback({ connectionId, url: result.authorizationUrl });
      }
    } catch (error) {
      stop();
      popup?.close();
      pending.current = null;
      throw error;
    }
  }

  return { open, fallback };
}
