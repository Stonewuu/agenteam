"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import Link from "next/link";
import {useEffect} from "react";
import {IconRefresh, IconSearch} from "@/components/ui/icons";

export function PageUnavailable({error, retry}: { error?: Error & { digest?: string }; retry?: () => void }) {
  const uiText = useT();
  useEffect(() => {
    if (error) {
      console.error("页面未能显示", {path: window.location.pathname, requestId: error.digest}, error.stack);
    }
  }, [error]);
  return <section className="page-unavailable"><span className="page-unavailable-icon">{retry ?
    <IconRefresh size={30}/> : <IconSearch size={30}/>}</span>
    <h1>{retry ? uiText("页面暂时无法打开") : uiText("没有找到此页面")}</h1>
    <p>{retry ? uiText("请重新加载，或返回工作台继续其他工作。") : uiText("页面可能已被移除，也可以从工作台重新进入。")}</p>
    <div>{retry && <Button className="button primary" onClick={retry}>{uiText("重新加载")}</Button>}<Link
      className="button" href="/">{uiText("返回工作台")}</Link></div>
  </section>;
}
