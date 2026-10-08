import {
  IconBell,
  IconChevronDown,
  IconChevronRight,
  IconClock,
  IconLayoutGrid,
  IconMenu2,
  IconPlus,
  IconSearch,
  IconSend
} from "./icons";

const icons = {
  bell: IconBell,
  clock: IconClock,
  "chevron-down": IconChevronDown,
  "chevron-right": IconChevronRight,
  collapse: IconLayoutGrid,
  menu: IconMenu2,
  "new-task": IconPlus,
  plus: IconPlus,
  search: IconSearch,
  send: IconSend
};

export function Icon({name, size = 18}: { name: keyof typeof icons; size?: number }) {
  const Component = icons[name];
  return <Component size={size}/>;
}
