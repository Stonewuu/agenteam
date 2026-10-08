"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {ResourceSummary} from "../types/resource";
import {ListingDialog} from "./resource-publish-dialog";
import ui from "@/components/ui/surface.module.css";
import styles from "./agent-listing-control.module.css";

export function AgentListingControl({enterpriseId, resource, onChanged}: {
  enterpriseId: string;
  resource: ResourceSummary;
  onChanged: () => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const [updated, setUpdated] = useState<ResourceSummary | null>(null);
  const [listingOpen, setListingOpen] = useState(false);
  const current = updated?.id === resource.id && BigInt(updated.revision) > BigInt(resource.revision) ? updated : resource;
  const listed = current.listing?.listed === true;
  const available = current.status === "active" && current.publishedVersion?.status === "available";
  const visible = listed && available;
  const label = listed ? visible ? uiText("已上架员工广场") : uiText("员工广场暂不可见") : uiText("未上架员工广场");
  return <div className={styles.control}>
    <div className={styles.row}><span className={`badge ${visible ? "mint" : "neutral"}`}>{label}</span>
      {resource.allowedActions.includes("listing") && <Button type="button" className={`${ui.button} ${styles.action}`}
                                                              aria-label={`${listed ? uiText("下架") : uiText("上架")}${resource.name}`}
                                                              disabled={action.busy || !listed && !available}
                                                              title={!listed && !available ? uiText("请先启用并发布可用版本") : undefined}
                                                              onClick={() => {
                                                                if (!listed) {
                                                                  setListingOpen(true);
                                                                  return;
                                                                }
                                                                void action.execute(async () => {
                                                                  const result = await action.mutation.run<ResourceSummary>(organizationPath(enterpriseId, `/agents/${encodeURIComponent(resource.id)}/listing`), {
                                                                    method: "PUT",
                                                                    revision: current.revision,
                                                                    body: {
                                                                      listed: false,
                                                                      hirePolicy: current.listing?.hirePolicy ?? "automatic"
                                                                    },
                                                                  });
                                                                  setUpdated(result);
                                                                  onChanged();
                                                                }, uiText("已从员工广场下架。"));
                                                              }}>{action.busy ? uiText("正在更新…") : listed ? uiText("下架") : uiText("上架")}</Button>}
    </div>
    {action.error && <MutationFeedback action={action} onReload={onChanged}/>}
    {listingOpen && <ListingDialog enterpriseId={enterpriseId} resource={current} startListing onSaved={setUpdated}
                                   onClose={() => setListingOpen(false)} onChanged={onChanged}/>}
  </div>;
}
