export type UploadFile = {
  path: string;
  size?: number;
  read: () => Promise<File>;
  error?: string;
};

export type UploadContents = {directories: string[]; files: UploadFile[]};
export type UploadSource =
  | {kind: "file"; name: string; contents: UploadContents}
  | {kind: "directory"; name: string; scan: (signal: AbortSignal) => Promise<UploadContents>};
export type PendingUpload = {source: UploadSource; contents: UploadContents | null; error?: string};

type LocalFile = {kind: "file"; name: string; read: () => Promise<File>};
type LocalFolder = {kind: "directory"; name: string; children: (signal: AbortSignal) => AsyncIterable<LocalFile | LocalFolder>};

function directorySource(root: LocalFolder): UploadSource {
  return {kind: "directory", name: root.name, async scan(signal) {
    const contents: UploadContents = {directories: [], files: []};
    const remaining = [{directory: root, path: root.name}];
    while (remaining.length) {
      signal.throwIfAborted();
      const {directory, path} = remaining.pop()!;
      contents.directories.push(path);
      for await (const child of directory.children(signal)) {
        signal.throwIfAborted();
        const relative = path + "/" + child.name;
        if (child.kind === "directory") {
          remaining.push({directory: child, path: relative});
        } else {
          contents.files.push({path: relative, read: child.read});
        }
      }
    }
    return contents;
  }};
}

function fromEntry(entry: FileSystemEntry): LocalFolder | LocalFile {
  if (entry.isDirectory) {
    const directory = entry as FileSystemDirectoryEntry;
    return {kind: "directory", name: entry.name, async *children(signal) {
      const reader = directory.createReader();
      // 同一个读取器可能分多批返回目录内容，空批次才表示已经读完。
      while (true) {
        signal.throwIfAborted();
        const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
        if (!batch.length) {
          return;
        }
        for (const child of batch) {
          yield fromEntry(child);
        }
      }
    }};
  }
  const file = entry as FileSystemFileEntry;
  return {kind: "file", name: file.name, read: () => new Promise<File>((resolve, reject) => file.file(resolve, reject))};
}

export function addParentDirectories(directories: Set<string>, path: string) {
  let parent = "";
  for (const part of path.split("/").slice(0, -1)) {
    parent = parent ? parent + "/" + part : part;
    directories.add(parent);
  }
}

export function selectedFiles(files: File[]): UploadSource[] {
  const sources: UploadSource[] = [];
  const folders = new Map<string, {directories: Set<string>; files: Map<string, UploadFile>}>();
  for (const file of files) {
    const path = file.webkitRelativePath || file.name;
    const item = {path, size: file.size, read: () => Promise.resolve(file)};
    if (!file.webkitRelativePath?.includes("/")) {
      sources.push({kind: "file", name: file.name, contents: {directories: [], files: [item]}});
      continue;
    }
    const name = path.slice(0, path.indexOf("/"));
    let folder = folders.get(name);
    if (!folder) {
      folder = {directories: new Set(), files: new Map()};
      folders.set(name, folder);
    }
    addParentDirectories(folder.directories, path);
    if (!folder.files.has(path)) {
      folder.files.set(path, item);
    }
  }
  for (const [name, folder] of folders) {
    sources.push({kind: "directory", name, scan: async () => ({directories: [...folder.directories], files: [...folder.files.values()]})});
  }
  return sources;
}

/** 拖放事件结束后浏览器会收回对拖放数据的访问，所以先取出入口，文件内容在确认上传后读取。 */
export function droppedSources(transfer: DataTransfer): UploadSource[] {
  const sources: UploadSource[] = [];
  const files: File[] = [];
  for (const item of Array.from(transfer.items ?? [])) {
    if (item.kind !== "file") {
      continue;
    }
    const entry = item.webkitGetAsEntry?.();
    if (entry?.isDirectory) {
      sources.push(directorySource(fromEntry(entry) as LocalFolder));
    } else {
      const file = item.getAsFile();
      if (file) {
        files.push(file);
      } else if (entry?.isFile) {
        const leaf = fromEntry(entry) as LocalFile;
        sources.push({kind: "file", name: leaf.name, contents: {directories: [], files: [{path: leaf.name, read: leaf.read}]}});
      }
    }
  }
  return sources.concat(selectedFiles(sources.length || files.length ? files : Array.from(transfer.files)));
}

type DirectoryHandle = FileSystemDirectoryHandle & {values: () => AsyncIterable<DirectoryHandle | FileSystemFileHandle>};
type FolderPicker = (options: {mode: "read"}) => Promise<DirectoryHandle>;

export function localFolderPicker(): FolderPicker | undefined {
  if (typeof window === "undefined") {
    return undefined;
  }
  return (window as Window & {showDirectoryPicker?: FolderPicker}).showDirectoryPicker?.bind(window);
}

export function selectedFolder(handle: DirectoryHandle): UploadSource {
  function folder(directory: DirectoryHandle): LocalFolder {
    return {kind: "directory", name: directory.name, async *children(signal) {
      for await (const child of directory.values()) {
        signal.throwIfAborted();
        yield child.kind === "directory" ? folder(child) : {kind: "file", name: child.name, read: () => child.getFile()};
      }
    }};
  }
  return directorySource(folder(handle));
}

export function pendingUpload(source: UploadSource): PendingUpload {
  return {source, contents: source.kind === "file" ? source.contents : null};
}
