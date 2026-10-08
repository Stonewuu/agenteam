import {ResourceIcon} from "./resource-icon";
import {resourceColors} from "./resource-appearance";
import styles from "./resource-avatar.module.css";

export function ResourceAvatar({icon, color = "purple", size = "medium"}: {
  icon: string;
  color?: string;
  size?: "tiny" | "small" | "medium" | "large"
}) {
  const tone = resourceColors.some(([value]) => value === color) ? color : "purple";
  const iconSize = size === "tiny" ? 12 : size === "large" ? 31 : size === "small" ? 19 : 24;
  return <span className={`avatar ${size} ${styles.avatar} ${styles[tone]}`}
               style={{background: "var(--resource-avatar-bg)", color: "var(--resource-avatar-fg)"}} aria-hidden="true"><ResourceIcon
    name={icon} size={iconSize}/></span>;
}
