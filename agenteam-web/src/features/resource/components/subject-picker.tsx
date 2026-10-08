"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";

import {useEffect, useState} from "react";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {type ApiPage, useApiPage} from "@/lib/http/use-api-query";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import {Pagination, QueryState} from "@/components/ui/query-state";
import type {Grant, SubjectOption} from "../types/resource";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";

export function SubjectPicker({enterpriseId, resourceId, type, purpose = "grant", onSelect}: {
  enterpriseId: string;
  resourceId: string;
  type: "team" | "user";
  purpose?: "grant" | "owner";
  onSelect: (value: SubjectOption) => void;
}) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const list = useApiPage<SubjectOption>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resourceId)}/subjects?subjectType=${type}&purpose=${purpose}&query=${encodeURIComponent(query)}`));
  return <div className={styles.options}><Input className={ui.input} type="search" value={query}
                                                onChange={(event) => setQuery(event.target.value)}
                                                aria-label={type === "user" ? uiText("搜索成员") : uiText("搜索团队")}
                                                placeholder={type === "user" ? uiText("搜索成员") : uiText("搜索团队")}/>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("暂无可选择的对象。")}>
      <div className={styles.optionList}>{list.data?.items.map((item) =>
        <Button className={ui.button} type="button" key={item.id} disabled={!item.active}
                onClick={() => onSelect(item)}>{item.name}</Button>)}</div>
    </QueryState>
    <Pagination {...list} hasMore={list.data?.hasMore}/>
  </div>;
}

export function useSubjectNames(enterpriseId: string, resourceId: string, grants: Grant[]) {
  const selected = JSON.stringify([...new Map(grants.filter((value) => value.subjectType !== "enterprise").map((value) => [`${value.subjectType}:${value.subjectId}`, {
    type: value.subjectType,
    id: value.subjectId
  }])).values()]);
  const [result, setResult] = useState<{ key: string; names: Record<string, SubjectOption>; error: string }>({
    key: "",
    names: {},
    error: ""
  });
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    const subjects = JSON.parse(selected) as { type: "team" | "user"; id: string }[];
    const calls: Promise<ApiPage<SubjectOption>>[] = [];
    for (const type of ["team", "user"]) {
      const ids = subjects.filter((item) => item.type === type).map((item) => item.id);
      for (let offset = 0; offset < ids.length; offset += 100) {
        calls.push(apiRequest<ApiPage<SubjectOption>>(organizationPath(enterpriseId,
          `/resources/${encodeURIComponent(resourceId)}/subjects?subjectType=${type}&subjectIds=${encodeURIComponent(ids.slice(offset, offset + 100).join(","))}`), {signal: controller.signal}));
      }
    }
    Promise.all(calls).then((pages) => {
      if (!controller.signal.aborted) {
        setResult({
          key: selected,
          names: Object.fromEntries(pages.flatMap((page) => page.items.map((item) => [`${item.subjectType}:${item.id}`, item]))),
          error: ""
        });
      }
    })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setResult({key: selected, names: {}, error: errorMessage(error)});
        }
      });
    return () => controller.abort();
  }, [enterpriseId, resourceId, selected, retry]);
  return {
    names: result.names,
    loading: result.key !== selected,
    error: result.error,
    retry: () => setRetry((value) => value + 1)
  };
}
