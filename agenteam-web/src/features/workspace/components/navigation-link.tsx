"use client";

import {useT} from "@/lib/i18n/locale-provider";

import Link, {useLinkStatus} from "next/link";
import type {ComponentProps} from "react";
import styles from "./navigation-link.module.css";

type NavigationLinkProps = Omit<ComponentProps<typeof Link>, "prefetch"> & { label: string };

/** 固定菜单提前读取完整页面，未就绪时按框架的真实导航状态提示等待。 */
export function NavigationLink({label, children, ...props}: NavigationLinkProps) {
  return <Link {...props} prefetch={true}>
    {children}
    <NavigationPending label={label}/>
  </Link>;
}

function NavigationPending({label}: { label: string }) {
  const uiText = useT();
  const {pending} = useLinkStatus();
  return <span className={styles.indicator} data-navigation-pending={pending} aria-hidden={!pending}
               role={pending ? "status" : undefined} aria-label={pending ? uiText("正在打开{0}…", [label]) : undefined}>
    <span className={styles.spinner}/>
  </span>;
}
