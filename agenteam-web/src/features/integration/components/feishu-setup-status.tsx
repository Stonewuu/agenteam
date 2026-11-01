"use client";

import { feishuSetupStatus } from "../lib/feishu-setup-status";
import type { FeishuSetupJob } from "../types/feishu-setup";
import { IntegrationSetupStatus } from "./integration-setup-status";

export function FeishuSetupStatus({ job }: { job: FeishuSetupJob }) {
  return <IntegrationSetupStatus name={job.name} content={feishuSetupStatus(job)} compact={job.canOpen} />;
}
