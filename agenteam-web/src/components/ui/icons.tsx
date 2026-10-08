"use client";

import type {CSSProperties} from "react";
import {
  Add,
  Archive,
  ArrowDown,
  ArrowDown2,
  ArrowLeft,
  ArrowLeft2,
  ArrowRight,
  ArrowRight2,
  ArrowUp,
  Book1,
  Bookmark,
  Box1,
  Buildings2,
  Calendar1,
  CalendarTick,
  Category2,
  Chart,
  Clock,
  Command,
  Copy,
  Cpu,
  Data,
  DirectInbox,
  Dislike,
  DocumentFilter,
  DocumentText,
  DocumentText1,
  Edit2,
  Element3,
  ExportCurve,
  ExportSquare,
  Eye,
  EyeSlash,
  Flag,
  Flash,
  Folder2,
  Global,
  Hierarchy,
  Hierarchy2,
  Hierarchy3,
  HierarchySquare2,
  type Icon,
  type IconProps,
  ImportCurve,
  InfoCircle,
  Key,
  LampOn,
  Layer,
  Like1,
  Link21,
  Lock1,
  Logout,
  Maximize4,
  Menu,
  Messages2,
  Minus,
  Monitor,
  Moon,
  More,
  NoteText,
  Notification,
  Paperclip2,
  Pause,
  People,
  Play,
  Profile2User,
  ProfileCircle,
  Record,
  Refresh,
  Save2,
  SearchNormal1,
  SecuritySafe,
  Send2,
  Setting2,
  Setting4,
  ShieldTick,
  SidebarLeft,
  SidebarRight,
  Sms,
  Star1,
  Stop,
  Sun1,
  Task,
  TaskSquare,
  TickCircle,
  Trash,
  Unlock,
  User,
  UserMinus,
} from "iconsax-reactjs";

// 统一使用 Iconsax 原始组件，交互图标默认使用线形，导航和资源图标可选 Bulk。
function agenteamIcon(Component: Icon, defaults: Partial<IconProps> = {}) {
  function AgenteamIcon({
                          variant = "Linear",
                          style,
                          className,
                          ...props
                        }: IconProps) {
    const rotation = defaults.style as CSSProperties | undefined;
    return (
      <Component
        aria-hidden="true"
        focusable="false"
        className={`iconsax-icon ${className || ""}`}
        variant={variant}
        {...defaults}
        {...props}
        style={{...rotation, ...style}}
      />
    );
  }

  return AgenteamIcon;
}

export const IconAdjustments = agenteamIcon(Setting4);
export const IconAlertCircle = agenteamIcon(InfoCircle);
export const IconArchive = agenteamIcon(Archive);
export const IconArrowDown = agenteamIcon(ArrowDown);
export const IconArrowLeft = agenteamIcon(ArrowLeft);
export const IconArrowRight = agenteamIcon(ArrowRight);
export const IconArrowUp = agenteamIcon(ArrowUp);
export const IconArrowUpRight = agenteamIcon(ArrowRight, {
  style: {rotate: "-45deg"},
});
export const IconArrowsJoin = agenteamIcon(Hierarchy2);
export const IconArrowsSplit = agenteamIcon(Hierarchy);
export const IconAt = agenteamIcon(DocumentFilter);
export const IconBell = agenteamIcon(Notification);
export const IconBook2 = agenteamIcon(Book1);
export const IconBookmark = agenteamIcon(Bookmark);
export const IconBuilding = agenteamIcon(Buildings2);
export const IconBulb = agenteamIcon(LampOn);
export const IconCalendar = agenteamIcon(Calendar1);
export const IconCalendarClock = agenteamIcon(CalendarTick);
export const IconChartBar = agenteamIcon(Chart);
export const IconCheck = agenteamIcon(TickCircle);
const TaskSquareIcon = agenteamIcon(TaskSquare);

export function IconCheckbox({variant = "Linear", className, ...props}: IconProps) {
  return <TaskSquareIcon variant={variant}
                         className={`${variant === "Bulk" ? "iconsax-task-square-bulk" : ""} ${className || ""}`} {...props} />;
}

export const IconThumbUp = agenteamIcon(Like1);
export const IconThumbDown = agenteamIcon(Dislike);
export const IconChevronDown = agenteamIcon(ArrowDown2);
export const IconChevronLeft = agenteamIcon(ArrowLeft2);
export const IconChevronRight = agenteamIcon(ArrowRight2);
export const IconChevronUp = agenteamIcon(ArrowDown2, {
  style: {rotate: "180deg"},
});
export const IconCircle = agenteamIcon(Record);
export const IconCircleCheck = agenteamIcon(TickCircle);
export const IconClock = agenteamIcon(Clock);
export const IconCommand = agenteamIcon(Command);
export const IconCopy = agenteamIcon(Copy);
export const IconDatabase = agenteamIcon(Data);
export const IconDeviceDesktop = agenteamIcon(Monitor);
export const IconDeviceFloppy = agenteamIcon(Save2);
// More 的 Bold 变体带实心外框，这里只填充线形版本的三个圆点。
export const IconDots = agenteamIcon(More, {variant: "Linear", style: {fill: "currentColor"}});
export const IconDownload = agenteamIcon(ImportCurve);
export const IconExternalLink = agenteamIcon(ExportSquare);
export const IconEye = agenteamIcon(Eye);
export const IconEyeOff = agenteamIcon(EyeSlash);
export const IconFileText = agenteamIcon(DocumentText);
export const IconFolder = agenteamIcon(Folder2);
export const IconFlag = agenteamIcon(Flag);
export const IconGitBranch = agenteamIcon(Hierarchy3);
export const IconHexagons = agenteamIcon(Box1);
export const IconHierarchy = agenteamIcon(Hierarchy2);
export const IconHistory = agenteamIcon(Clock);
export const IconInbox = agenteamIcon(DirectInbox);
export const IconKey = agenteamIcon(Key);
export const IconLanguage = agenteamIcon(Global);
export const IconLayoutDashboard = agenteamIcon(Element3);
export const IconLayoutGrid = agenteamIcon(Category2);
export const IconSidebarLeft = agenteamIcon(SidebarLeft);
export const IconSidebarRight = agenteamIcon(SidebarRight);
export const IconList = agenteamIcon(Task);
export const IconListDetails = agenteamIcon(DocumentText1);
export const IconLock = agenteamIcon(Lock1);
export const IconUnlock = agenteamIcon(Unlock);
export const IconLogout = agenteamIcon(Logout);
export const IconMail = agenteamIcon(Sms);
export const IconMaximize = agenteamIcon(Maximize4);
export const IconMenu2 = agenteamIcon(Menu);
export const IconMessages = agenteamIcon(Messages2);
export const IconMinus = agenteamIcon(Minus);
export const IconMoon = agenteamIcon(Moon);
export const IconNotes = agenteamIcon(NoteText);
export const IconPaperclip = agenteamIcon(Paperclip2);
export const IconPencil = agenteamIcon(Edit2);
export const IconPlayerPause = agenteamIcon(Pause);
export const IconPlayerPlay = agenteamIcon(Play);
export const IconPlayerStop = agenteamIcon(Stop);
export const IconPlug = agenteamIcon(Link21);
export const IconPlus = agenteamIcon(Add);
export const IconRefresh = agenteamIcon(Refresh);
export const IconRobot = agenteamIcon(Cpu);
export const IconSchema = agenteamIcon(HierarchySquare2);
export const IconSearch = agenteamIcon(SearchNormal1);
export const IconSend = agenteamIcon(Send2);
export const IconSettings = agenteamIcon(Setting2);
export const IconShield = agenteamIcon(SecuritySafe);
export const IconShieldCheck = agenteamIcon(ShieldTick);
export const IconSparkles = agenteamIcon(Flash);
export const IconStack = agenteamIcon(Layer);
export const IconStar = agenteamIcon(Star1);
export const IconSun = agenteamIcon(Sun1);
export const IconTrash = agenteamIcon(Trash);
export const IconUpload = agenteamIcon(ExportCurve);
export const IconUser = agenteamIcon(User);
export const IconUserCircle = agenteamIcon(ProfileCircle);
export const IconUserMinus = agenteamIcon(UserMinus);
export const IconUsers = agenteamIcon(Profile2User);
export const IconUsersGroup = agenteamIcon(People);
export const IconX = agenteamIcon(Add, {style: {rotate: "45deg"}});
