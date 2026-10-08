"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {createContext, type ReactNode, useContext} from "react";
import {usePageHeaderMotion} from "./use-page-header-motion";
import styles from "./page-header.module.css";

type PageHeaderProps = {
  title: ReactNode; description?: ReactNode; eyebrow?: ReactNode; actions?: ReactNode;
  leading?: ReactNode; metadata?: ReactNode; inlineActions?: boolean; size?: "normal" | "small" | "hero";
  className?: string; icon?: ReactNode;
};

const PageHeaderIconContext = createContext<ReactNode>(null);

export function PageHeaderIconProvider({icon, children}: { icon: ReactNode; children: ReactNode }) {
  return <PageHeaderIconContext.Provider value={icon}>{children}</PageHeaderIconContext.Provider>;
}

/** 标题和原有操作连续收紧，同一组按钮始终保留状态和焦点。 */
export function PageHeader({
                             title,
                             description,
                             eyebrow,
                             actions,
                             leading,
                             metadata,
                             inlineActions = false,
                             size = "normal",
                             className = "",
                             icon
                           }: PageHeaderProps) {
  const uiText = useT();
  const {frame, surface} = usePageHeaderMotion();
  const menuIcon = useContext(PageHeaderIconContext);
  const resolvedIcon = icon ?? menuIcon;
  const identity = leading ?? (resolvedIcon &&
    <span className={styles.menuIcon} data-page-icon aria-hidden="true">{resolvedIcon}</span>);
  return <header ref={frame} data-page-header data-size={size} data-inline-actions={inlineActions}
                 className={`${styles.frame} ${className}`}>
    <div ref={surface} className={styles.surface}>
      <div className={styles.identity}>{identity && <div className={styles.leading}>{identity}</div>}
        <div className={styles.copy}>
          {eyebrow && <p data-header-secondary className={styles.eyebrow}>{eyebrow}</p>}
          <div data-header-title-line data-has-metadata={Boolean(metadata)} className={styles.titleLine}>
            <h1 className={styles.title} title={typeof title === "string" ? title : undefined}>{title}</h1>
            {metadata && <div data-header-metadata className={styles.metadata}>{metadata}</div>}
          </div>
          {description && <p data-header-secondary className={styles.description}>{description}</p>}
        </div>
      </div>
      {actions && <div data-header-actions role="group" aria-label={uiText("页面操作")}
                       className={styles.actions}>{actions}</div>}
    </div>
  </header>;
}
