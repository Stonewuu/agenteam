import {IconBell, IconCalendarClock, IconCheckbox, IconMessages, IconUser} from "@/components/ui/icons";

export function NotificationIcon({targetType, size = 20}: { targetType: string | null; size?: number }) {
  const Icon = targetType === "conversation" ? IconMessages : targetType === "schedule" ? IconCalendarClock
    : targetType === "todo" ? IconCheckbox : targetType === "hire_request" ? IconUser : IconBell;
  return <Icon size={size}/>;
}
