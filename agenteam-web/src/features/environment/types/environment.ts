export type Environment = {
  enabled: boolean; status: "running" | "stopped" | "stopping"; busy: boolean;
  idleSeconds: number; lastActiveAt: number | null;
};
export type TerminalView = {
  id: string; status: "starting" | "running" | "closing" | "closed" | "error";
  output: string; start: number; end: number; sequence: number; directory: string;
  busy: boolean; cols: number; rows: number; error: string | null;
};
export type TerminalInput = {
  action: "open" | "poll" | "input" | "resize" | "close";
  id: string; data?: string; sequence: number; cursor: number; cols: number; rows: number;
};
export type EnvironmentFile = {
  name: string; path: string; directory: boolean; size: number | null; modifiedAt: string;
  projectName: string | null; deletable: boolean;
};
export type EnvironmentUsage = {fileCount: number; totalBytes: number; sampleId: string};
export type EnvironmentDirectory = {path: string; entries: EnvironmentFile[]; nextOffset: number | null; total: number};
export type EnvironmentPreview = {name: string; path: string; size: number; binary: boolean; truncated: boolean; content: string};
