"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useEffect, useRef, useState} from "react";
import {ApiMutation, apiRequest, errorMessage} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {type FilePurpose, uploadFile, validateUploadFile} from "../api/file-api";
import type {FileReference} from "@/features/agent/types/execution";
import {fileFailureText} from "../lib/file-status";

export type UploadSelection = {
  localId: string;
  file: File;
  fileId: string | null;
  reference: FileReference | null;
  busy: boolean;
  started: boolean;
  error: string
};
type Group = { items: UploadSelection[]; error: string };

/** 对话草稿分别保留资料选择；服务器确认可用后才能交给提交接口。 */
export function useFileUploads(enterpriseId: string, purpose: FilePurpose, scope: string, resourceId: string | null = null) {
  const uiText = useT();
  const [groups, setGroups] = useState<Record<string, Group>>({});
  const current = useRef<Record<string, Group>>({});
  const active = useRef(new Map<string, AbortController>());
  const mutations = useRef(new Map<string, ApiMutation>());
  const mounted = useRef(true);
  const queues = useRef([Promise.resolve(), Promise.resolve()]);
  const next = useRef(0);
  const key = `${enterpriseId}:${purpose}:${resourceId ?? ""}:${scope}`;
  useEffect(() => {
    mounted.current = true;
    const controllers = active.current;
    return () => {
      mounted.current = false;
      controllers.forEach((controller) => controller.abort());
    };
  }, []);

  function update(groupKey: string, transform: (value: Group) => Group) {
    if (!mounted.current) {
      return;
    }
    const value = transform(current.current[groupKey] ?? {items: [], error: ""});
    current.current = {...current.current, [groupKey]: value};
    setGroups(current.current);
  }

  function patch(groupKey: string, id: string, value: Partial<UploadSelection>) {
    update(groupKey, (group) => ({
      ...group,
      items: group.items.map((item) => item.localId === id ? {...item, ...value} : item)
    }));
  }

  async function process(groupKey: string, id: string) {
    const item = current.current[groupKey]?.items.find((value) => value.localId === id);
    if (!item || !mounted.current) {
      return;
    }
    const controller = new AbortController();
    active.current.set(id, controller);
    const mutation = mutations.current.get(id) ?? new ApiMutation();
    mutations.current.set(id, mutation);
    patch(groupKey, id, {busy: true, started: true, error: ""});
    try {
      let reference = await uploadFile({
        enterpriseId,
        file: item.file,
        purpose,
        resourceId,
        mutation,
        signal: controller.signal,
        existingId: item.fileId,
        onPrepared: (fileId) => patch(groupKey, id, {fileId})
      });
      while (!controller.signal.aborted) {
        patch(groupKey, id, {reference, fileId: reference.id});
        if (reference.status === "ready") {
          break;
        }
        if (reference.status === "rejected" || reference.status === "deleted") {
          throw new Error(uiText(fileFailureText(reference)));
        }
        await pause(controller.signal);
        reference = await apiRequest<FileReference>(organizationPath(enterpriseId, `/files/${encodeURIComponent(reference.id)}`), {signal: controller.signal});
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        patch(groupKey, id, {error: errorMessage(failure)});
      }
    } finally {
      active.current.delete(id);
      if (!controller.signal.aborted) {
        patch(groupKey, id, {busy: false});
      }
    }
  }

  function enqueue(groupKey: string, id: string) {
    const slot = next.current++ % queues.current.length;
    queues.current[slot] = queues.current[slot].then(() => process(groupKey, id));
  }

  function add(files: File[]) {
    const selected = current.current[key]?.items ?? [];
    try {
      const max = purpose === "data_import" || purpose === "skill_import" ? 1 : 10;
      if (selected.length + files.length > max) {
        throw new Error(uiText("每次最多选择 {0} 份文件。", [max]));
      }
      files.forEach((file) => validateUploadFile(file, purpose));
      if (purpose === "attachment" && [...selected.map((item) => item.file), ...files].reduce((total, file) => total + file.size, 0) > 50 * 1024 * 1024) {
        throw new Error(uiText("本次资料总大小不能超过 50 兆字节。"));
      }
      const added = files.map((file): UploadSelection => ({
        localId: crypto.randomUUID(),
        file,
        fileId: null,
        reference: null,
        busy: true,
        started: false,
        error: ""
      }));
      update(key, (group) => ({items: [...group.items, ...added], error: ""}));
      added.forEach((item) => enqueue(key, item.localId));
    } catch (failure) {
      update(key, (group) => ({...group, error: errorMessage(failure)}));
    }
  }

  function remove(id: string) {
    active.current.get(id)?.abort();
    mutations.current.delete(id);
    update(key, (group) => ({items: group.items.filter((item) => item.localId !== id), error: ""}));
  }

  function clear() {
    for (const item of current.current[key]?.items ?? []) {
      active.current.get(item.localId)?.abort();
      mutations.current.delete(item.localId);
    }
    update(key, () => ({items: [], error: ""}));
  }

  const group = groups[key] ?? {items: [], error: ""};
  return {
    ...group, add, remove, clear, retry: (id: string) => {
      patch(key, id, {busy: true, error: ""});
      enqueue(key, id);
    },
    ready: group.items.length > 0 && group.items.every((item) => item.reference?.status === "ready" && !item.error),
    fileIds: group.items.flatMap((item) => item.reference?.status === "ready" && !item.error ? [item.reference.id] : [])
  };
}

function pause(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason);
      return;
    }
    const timer = window.setTimeout(() => {
      signal.removeEventListener("abort", cancel);
      resolve();
    }, 1000);

    function cancel() {
      window.clearTimeout(timer);
      signal.removeEventListener("abort", cancel);
      reject(signal.reason);
    }

    signal.addEventListener("abort", cancel, {once: true});
  });
}
