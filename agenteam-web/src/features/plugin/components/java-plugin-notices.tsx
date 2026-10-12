"use client";

import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {Button} from "@/components/ui/button";
import {AnimatedHeight} from "@/components/ui/animated-height";
import ui from "@/components/ui/surface.module.css";

type Notice = {versionId: string; resourceId: string; name: string; status: "upgraded" | "uninstalled"};

export function JavaPluginNotices({enterpriseId, versions}: {enterpriseId: string; versions: string[]}) {
  const t = useT();
  const query = useApiQuery<Notice[]>(versions.length
    ? `/api/v1/enterprises/${enterpriseId}/plugins/java-packages/references?versionIds=${encodeURIComponent(versions.join(","))}` : null);
  return <AnimatedHeight preserveControlShadows>
    {query.error && <div role="alert"><p className={ui.error}>{query.error}</p>
      <Button type="button" className={ui.button} onClick={query.retry}>{t("重新读取")}</Button></div>}
    {query.data?.map((notice) => <p key={notice.versionId} role="status" className={ui.description}>
      {notice.name}：{t(notice.status === "upgraded" ? "插件有更新，请确认并重新选择插件版本。" : "插件已下架，请移除。")}
    </p>)}
  </AnimatedHeight>;
}
