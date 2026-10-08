export type UsageItem = {
  policyId: string;
  subjectType: "enterprise" | "team" | "role";
  subjectName: string;
  usedCount: number;
  reservedCount: number;
  monthlyLimit: number | null;
  remainingCount: number | null
};
export type Usage = { periodStart: string; periodEnd: string; timezone: string; items: UsageItem[] };
