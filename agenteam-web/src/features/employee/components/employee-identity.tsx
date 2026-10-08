import {ResourceAvatar} from "@/components/ui/resource-avatar";
import styles from "./employee-identity.module.css";

export function EmployeeIdentity({name, icon, color, size = "tiny"}: {
  name: string; icon?: string | null; color?: string | null; size?: "tiny" | "small";
}) {
  return <span className={styles.identity}><ResourceAvatar icon={icon ?? ""} color={color ?? undefined}
                                                           size={size}/><span
    className={styles.name}>{name}</span></span>;
}
