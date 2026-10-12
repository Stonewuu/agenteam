export type UsageToken = {value: string | null; state: string};
export type ModelUsageTotals = {
  firstRequestAt: string | null; calls: string; incompleteUsageCalls: string; unpricedCalls: string; pendingCalls: string; invalidUsageCalls: string;
  tokens: Record<string, {value: string | null; knownCalls: string; notApplicableCalls: string}>;
  amounts: {currency: string; completeAmount: string | null; completeCalls: string; partialAmount: string | null; partialCalls: string}[];
};
export type ModelUsageSummary = {
  from: string | null; to: string | null; timezone: string; totals: ModelUsageTotals;
  days: {date: string; totals: ModelUsageTotals}[];
};
export type ModelCall = {
  id: string; conversationId: string | null; runId: string | null; userId: string; modelProfileId: string;
  modelName: string; providerName: string; protocol: string; purpose: string; workflowNodeId: string | null;
  modelProfileName: string | null; userName: string | null;
  callStatus: string; usageStatus: string; priceStatus: string; reasonCode: string | null; dispatchedAt: string; finishedAt: string | null;
  tokens: Record<string, UsageToken>; cacheWriteDetails: Record<string, string>; currency: string | null;
  totalAmount: string | null; knownAmount: string | null; priceVersionId: string | null; priceRuleId: string | null;
  items: {meter: string; cacheTtlSeconds: number; quantity: string; pricePerMillion: string; amount: string}[];
};
export type ConversationModelUsage = {
  summary: ModelUsageSummary;
  calls: {items: ModelCall[]; nextCursor: string | null; hasMore: boolean};
};
