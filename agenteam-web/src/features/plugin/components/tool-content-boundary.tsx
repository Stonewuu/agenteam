"use client";

import {Component, type ReactNode} from "react";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./tool-payload.module.css";

/** 单条工具内容显示失败时，仍可切换原始视图或继续阅读对话。 */
export class ToolContentBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = {failed: false};

  static getDerivedStateFromError() {
    return {failed: true};
  }

  componentDidCatch(error: Error) {
    console.error("工具内容显示失败", error);
  }

  render() {
    return this.state.failed ? <ContentFailure/> : this.props.children;
  }
}

function ContentFailure() {
  const t = useT();
  return <div className={styles.contentNotice} role="alert">{t("内容暂时无法显示，请切换原始视图或重新展开。")}</div>;
}
