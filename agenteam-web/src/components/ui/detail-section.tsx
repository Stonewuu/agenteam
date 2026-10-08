"use client";

import {type ReactNode, useId} from "react";
import {Switch} from "./shadcn/switch";
import {useControlDisabled} from "./fieldset";
import styles from "./detail-section.module.css";

export function DetailHeading({icon, title, description, action, level = "h3"}: {
  icon: ReactNode; title: string; description?: ReactNode; action?: ReactNode; level?: "h2" | "h3";
}) {
  const Title = level;
  return <header className={styles.heading}>
    <span className={styles.icon}>{icon}</span>
    <div className={styles.headingCopy}><Title>{title}</Title>{description && <p>{description}</p>}</div>
    {action && <div className={styles.headingAction}>{action}</div>}
  </header>;
}

export function DetailSection({icon, title, description, action, children, className = "", level = "h3"}: {
  icon: ReactNode; title: string; description?: ReactNode; action?: ReactNode; children: ReactNode;
  className?: string; level?: "h2" | "h3";
}) {
  return <section className={`${styles.section} ${className}`}>
    <DetailHeading icon={icon} title={title} description={description} action={action} level={level}/>
    <div className={styles.sectionBody}>{children}</div>
  </section>;
}

export function DetailStatus({children, icon, tone = "neutral"}: {
  children: ReactNode; icon?: ReactNode; tone?: "neutral" | "success" | "warning" | "danger" | "accent";
}) {
  return <span className={styles.status} data-tone={tone}>{icon}{children}</span>;
}

/** 布尔设置统一显示名称、用途及右侧开关，保存行为由所在页面或表单决定。 */
export function SettingRow({icon, label, description, checked, disabled = false, onChange}: {
  icon: ReactNode; label: string; description?: string; checked: boolean; disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  const id = useId();
  return <div className={styles.setting} data-disabled={disabled || undefined}>
    <span className={styles.settingIcon}>{icon}</span>
    <label htmlFor={id}><strong>{label}</strong>{description && <span id={`${id}-description`}>{description}</span>}</label>
    <Switch id={id} className="agenteam-toggle" checked={checked} disabled={useControlDisabled(disabled)}
      onCheckedChange={onChange} aria-label={label} aria-describedby={description ? `${id}-description` : undefined}/>
  </div>;
}
