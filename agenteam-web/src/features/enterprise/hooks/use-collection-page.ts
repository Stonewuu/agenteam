"use client";

import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "../api/organization-api";

export function useCollectionPage<T>(enterpriseId: string, collection: string, query: string, refresh = 0, limit = 30) {
  const list = useApiPage<T>(organizationPath(enterpriseId, `/${collection}?query=${encodeURIComponent(query)}`), refresh, query ? 200 : 0, limit);
  return {...list, page: list.data};
}
