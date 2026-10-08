"use client";

import {usePathname, useSearchParams} from "next/navigation";
import type {CollectionView} from "@/components/ui/collection-view";

/** 仅更新展示方式，保留当前地址的其他参数，不重新请求列表。 */
export function useCollectionView() {
  const pathname = usePathname();
  const params = useSearchParams();
  const view: CollectionView = params.get("view") === "list" ? "list" : "grid";

  function changeView(nextView: CollectionView) {
    const next = new URLSearchParams(params.toString());
    if (nextView === "list") {
      next.set("view", "list");
    } else {
      next.delete("view");
    }
    window.history.replaceState(null, "", `${pathname}${next.size ? `?${next}` : ""}${window.location.hash}`);
  }

  return [view, changeView] as const;
}
