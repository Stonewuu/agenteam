"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useEffect, useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {loadIdentity, signOut} from "../api/identity-api";
import {landingPath} from "../lib/identity-navigation";
import {useFormAction} from "../hooks/use-form-action";
import {IconBuilding} from "@/components/ui/icons";
import {ThemeMenu} from "./theme-menu";
import styles from "./auth.module.css";

export function NoEnterprisePage() {
  const uiText = useT();
  const router = useRouter();
  const action = useFormAction();
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    loadIdentity(controller.signal).then((user) => {
      if (controller.signal.aborted) {
        return;
      }
      if (!user) {
        router.replace("/login");
      } else if (user.enterprises.length) {
        router.replace(landingPath(user));
      } else {
        setLoaded(true);
      }
    }).catch((failure) => {
      if (!controller.signal.aborted) {
        console.error("读取企业资格失败", failure);
        setError("暂时无法读取账号信息，请重试。");
      }
    });
    return () => controller.abort();
  }, [router, retry]);
  return <main className={styles.page}><ThemeMenu/>
    <section className={styles.card}>
      <IconBuilding size={40} variant="Bulk"/><h1
      className={styles.title}>{loaded ? uiText("尚未加入企业") : uiText("正在读取账号信息")}</h1>
      <p
        className={styles.description}>{loaded ? uiText("你可以通过企业邀请邮件中的链接加入工作空间。") : uiText("请稍候…")}</p>
      {(error || action.error) && <p role="alert" className={styles.error}>{error || action.error}</p>}
      {error && <Button className={styles.buttonSecondary} onClick={() => {
        setError("");
        setRetry((value) => value + 1);
      }}>{uiText("重新加载")}</Button>}
      {loaded && <div className={styles.actions}><Link className={styles.textLink}
                                                       href="/settings">{uiText("个人设置")}</Link><Button
        className={styles.buttonSecondary} disabled={action.busy} onClick={() => void action.execute(async () => {
        await signOut();
        router.replace("/login");
      }, "")}>{uiText("退出登录")}</Button></div>}
    </section>
  </main>;
}
