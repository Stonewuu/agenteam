"use client";


import {useEffect, useRef, useState} from "react";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import type {ApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {SkillOption} from "../types/skill";

/** 选择随本人对话草稿保存，工作台入口再核对当前员工的真实候选。 */
export function useConversationSkills(userId: string, enterprise: string, agent: string | null, conversation: string | null, requested: string | null, enabled: boolean) {
  const keyFor = (agentId: string | null) => `${userId}:${enterprise}:new:${agentId ?? ""}`;
  const key = conversation ? `${userId}:${enterprise}:${conversation}` : keyFor(agent);
  const [drafts, setDrafts] = useState<Record<string, { selected: SkillOption[]; error: string; seed?: string }>>({});
  const seeded = useRef<string | null>(null);
  const loading = useRef<AbortController | null>(null);
  useEffect(() => {
    if (!requested || conversation || !agent || !enabled) {
      seeded.current = null;
      return;
    }
    const requestKey = `${key}:${requested}`;
    if (seeded.current === requestKey) {
      return;
    }
    seeded.current = requestKey;
    const controller = new AbortController();
    loading.current = controller;

    async function load() {
      let cursor: string | null = null;
      do {
        const page: ApiPage<SkillOption> = await apiRequest(organizationPath(enterprise, `/agents/${encodeURIComponent(agent!)}/input-options?kind=skill&limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`), {signal: controller.signal});
        const selected = page.items.find((value) => value.versionId === requested);
        if (selected) {
          if (!controller.signal.aborted) {
            setDrafts((current) => ({...current, [key]: {selected: [selected], error: "", seed: requested!}}));
          }
          return;
        }
        cursor = page.hasMore ? page.nextCursor : null;
      } while (cursor && !controller.signal.aborted);
      throw new Error("所选技能当前无法使用，请重新选择。");
    }

    void load().catch((error) => {
      if (!controller.signal.aborted) {
        setDrafts((current) => ({
          ...current,
          [key]: {selected: current[key]?.selected ?? [], error: errorMessage(error), seed: requested!}
        }));
      }
    });
    return () => controller.abort();
  }, [userId, enterprise, agent, conversation, requested, enabled, key]);
  const choose = (selected: SkillOption[]) => {
    loading.current?.abort();
    setDrafts((current) => ({
      ...current,
      [key]: {
        selected: selected.filter((value, index, all) => all.findIndex((item) => item.versionId === value.versionId) === index).slice(0, 3),
        error: "",
        seed: requested ?? undefined
      }
    }));
  };
  return {
    selected: drafts[key]?.selected ?? [], error: drafts[key]?.error ?? "", choose, clear: () => choose([]),
    loading: Boolean(requested && !conversation && agent && enabled && drafts[key]?.seed !== requested),
    forNew: (skill: SkillOption, agentId: string) => setDrafts((current) => ({
      ...current,
      [keyFor(agentId)]: {selected: [skill], error: ""}
    }))
  };
}
