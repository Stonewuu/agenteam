import type {ReactNode} from "react";
import styles from "./guidance.module.css";

/** 将当前操作的必要说明与下一步放在一起，颜色始终配合标题和图标。 */
export function Guidance({title, icon, children, actions, tone = "info"}: {
  title: string; icon: ReactNode; children: ReactNode; actions?: ReactNode;
  tone?: "info" | "warning" | "success";
}) {
  return <section className={styles.guidance} data-tone={tone} aria-label={title}>
    <span className={styles.icon} aria-hidden="true">{icon}</span>
    <div className={styles.copy}><strong>{title}</strong><div className={styles.description}>{children}</div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </div>
  </section>;
}
