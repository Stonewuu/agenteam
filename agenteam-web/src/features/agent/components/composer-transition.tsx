"use client";

import {type ReactNode, useLayoutEffect} from "react";
import {useComposerHandoff} from "./composer-handoff-context";
import "./composer-transition.css";

export const composerSendTransition = "composer-send";

/** 仅首页发送成功时连接两个输入框；普通导航和输入不触发过渡。 */
export function ComposerTransition({part, children}: {
  part: "surface" | "text" | "employee" | "tools" | "policy" | "submit"; children: ReactNode;
}) {
  const handoff = useComposerHandoff();
  const arrived = handoff?.arrived;
  useLayoutEffect(() => {
    if (part !== "surface" || !arrived) {
      return;
    }
    let cancelled = false;
    // 页面截图等待期间浏览器可能暂停绘制；布局提交后的微任务仍会执行。
    queueMicrotask(() => {
      if (!cancelled) {
        arrived();
      }
    });
    return () => {
      cancelled = true;
    };
  }, [part, arrived, handoff?.value?.conversationId]);
  return <>{children}</>;
}
