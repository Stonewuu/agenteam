"use client";

import {useEffect} from "react";
import {Button} from "@/components/ui/button";
import {BrandIcon} from "@/components/ui/brand-icon";
import {IconArrowRight, IconBuilding} from "@/components/ui/icons";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {type ChannelLoginOption, startChannelLogin} from "../api/channel-login-api";
import styles from "./channel-login-options.module.css";

export function ChannelLoginOptions({disabled, onBusyChange}: {
  disabled: boolean;
  onBusyChange: (busy: boolean) => void;
}) {
  const t = useT();
  const query = useApiQuery<ChannelLoginOption[]>("/api/v1/auth/channel-login");
  const action = useFormAction();
  useEffect(() => {
    onBusyChange(action.busy);
    return () => onBusyChange(false);
  }, [action.busy, onBusyChange]);

  const state = query.loading ? "loading" : query.error ? "error" : query.data?.length ? "ready" : "empty";
  return <LoadingTransition state={state}>
    {state !== "empty" && <div className={styles.options}>
      <div className={styles.separator}>{t("其他登录方式")}</div>
      {query.loading ? <p className={styles.status} role="status">{t("正在加载登录方式…")}</p>
        : query.error ? <div className={styles.failure}>
          <span role="alert">{t("暂时无法加载企业登录方式。")}</span>
          <Button type="button" className="text-button" disabled={disabled || action.busy} onClick={query.retry}>{t("重试")}</Button>
        </div> : <div className={styles.buttons}>
          {query.data?.map(option => {
            const duplicate = query.data!.filter(item => item.providerCode === option.providerCode).length > 1;
            return <Button key={option.key} type="button" className={styles.option} disabled={disabled || action.busy}
                           onClick={() => void action.execute(async () => {
                             const result = await startChannelLogin(option.key);
                             window.location.assign(result.authorizationUrl);
                           }, "")}>
              {option.providerCode === "feishu" ? <BrandIcon brand="feishu" size={22}/> : <IconBuilding size={20} variant="Bulk"/>}
              <span className={styles.label}><span>{t("通过 {platform} 登录", {platform: t(option.providerName)})}</span>
                {duplicate && <small>{option.name}</small>}</span>
              <IconArrowRight size={17}/>
            </Button>;
          })}
        </div>}
      <MutationFeedback action={action}/>
    </div>}
  </LoadingTransition>;
}
