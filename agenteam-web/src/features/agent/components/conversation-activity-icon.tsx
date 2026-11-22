import styles from "./conversation-activity-icon.module.css";

/** 对话等待与工具执行共用圆点动画，状态文字由调用位置提供。 */
export function ConversationActivityIcon() {
  return <span className={styles.fluid} aria-hidden="true"><i/><i/><i/></span>;
}
