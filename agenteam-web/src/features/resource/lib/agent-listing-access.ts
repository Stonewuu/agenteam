import type {Grant, ResourceSummary} from "../types/resource";

/** 新上架且尚未设置范围时默认开放给全员；已经上架的人工范围保持不变。 */
export function initialAgentUseGrants(resource: ResourceSummary, grants: Grant[], enterpriseId: string): Grant[] {
  const current = grants.filter((grant) => grant.capability === "use");
  return current.length || resource.listing?.listed ? current : [{
    subjectType: "enterprise",
    subjectId: enterpriseId,
    capability: "use"
  }];
}

export function replaceAgentUseGrants(grants: Grant[], useGrants: Grant[]): Grant[] {
  return [...grants.filter((grant) => grant.capability !== "use"), ...useGrants];
}
