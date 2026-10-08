"use client";

import {
  Bag2,
  Book1,
  BookSaved,
  Box1,
  Cpu,
  Data,
  DocumentText,
  Edit2,
  FolderOpen,
  Hierarchy,
  LampOn,
  Magicpen,
  MagicStar,
  Notepad2,
  Scan,
  SearchNormal1
} from "iconsax-reactjs";

const icons = {
  Sparkles: MagicStar,
  Feather: Edit2,
  Telescope: SearchNormal1,
  ShoppingBag: Bag2,
  NotebookPen: Notepad2,
  Lightbulb: LampOn,
  WandSparkles: Magicpen,
  BookOpen: Book1,
  Box: Box1,
  FileText: DocumentText,
  GitBranch: Hierarchy,
  Library: BookSaved,
  Folders: FolderOpen,
  Database: Data,
  ScanText: Scan
};

export function ResourceIcon({name, size = 22}: { name: string; size?: number }) {
  const Icon = icons[name as keyof typeof icons] ?? Cpu;
  return <Icon size={size} variant="Bulk" color="currentColor" aria-hidden="true"/>;
}
