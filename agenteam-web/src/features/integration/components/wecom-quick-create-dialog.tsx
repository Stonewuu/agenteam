"use client";

import Image from "next/image";
import { useId, useState } from "react";
import { AnimatedHeight } from "@/components/ui/animated-height";
import { Button } from "@/components/ui/button";
import { Dialog, DialogActions, DialogCancel, DialogForm } from "@/components/ui/dialog";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { QueryState } from "@/components/ui/query-state";
import { IconCheck, IconCopy, IconExternalLink, IconPlug, IconRefresh, IconRobot } from "@/components/ui/icons";
import { useFormAction } from "@/features/auth/hooks/use-form-action";
import { MutationFeedback } from "@/features/workspace/components/mutation-feedback";
import { ChatAgentPicker } from "@/features/chat/components/chat-agent-picker";
import { chatPath } from "@/features/chat/types/chat";
import { useApiQuery } from "@/lib/http/use-api-query";
import { useT } from "@/lib/i18n/locale-provider";
import { integrationPath } from "../types/integration";
import { finishedWecomStages, type WecomSetupJob, type WecomSetupOverview } from "../types/wecom-setup";
import { wecomSetupStatus } from "../lib/wecom-setup-status";
import { IntegrationSetupSteps } from "./integration-setup-steps";
import { IntegrationSetupStatus } from "./integration-setup-status";
import { IntegrationEditor } from "./integration-editor";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration-quick-create.module.css";

function refreshWhileCreating(data: WecomSetupOverview | null) {
  return data?.jobs.some((job) => !finishedWecomStages.has(job.stage) && job.stage !== "failed") ? 2500 : 0;
}

export function WecomQuickCreateDialog({
  enterpriseId,
  system,
  onClose,
  onSaved,
}: {
  enterpriseId: string;
  system: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const t = useT();
  const path = chatPath(enterpriseId, system);
  const base = path + "/wecom";
  const [refresh, setRefresh] = useState(0);
  const overview = useApiQuery<WecomSetupOverview>(base, refresh, 0, false, { refreshInterval: refreshWhileCreating });
  const [activeId, setActiveId] = useState<string | null>(null);
  const [created, setCreated] = useState<WecomSetupJob | null>(null);
  const [parentId, setParentId] = useState<string | null>(null);
  const [enterpriseEditor, setEnterpriseEditor] = useState(false);
  const [agentId, setAgentId] = useState("");
  const [agentVersion, setAgentVersion] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [agentError, setAgentError] = useState("");
  const [copiedName, setCopiedName] = useState("");
  const [copyError, setCopyError] = useState(false);
  const formId = useId();
  const action = useFormAction();
  const jobs = overview.data?.jobs ?? [];
  const resumed = jobs.find((item) => !finishedWecomStages.has(item.stage));
  const chosenId = activeId === null ? resumed?.id : activeId;
  const loaded = jobs.find((item) => item.id === chosenId);
  const local = created?.id === chosenId ? created : null;
  const job = loaded && (!local || loaded.revision >= local.revision) ? loaded : local;
  const parents = overview.data?.enterprises ?? [];
  const selectedParentId = job?.enterpriseConnectionId ?? parentId;
  const parent = selectedParentId ? parents.find((item) => item.id === selectedParentId) : parents[0];
  const complete = job?.stage === "completed";
  const close = complete ? onSaved : onClose;
  const step = !parent
    ? "connect"
    : !job || job.stage === "queued" || job.stage === "awaiting_scan"
      ? "create"
      : complete
        ? "complete"
        : "configure";
  const changed = () => setRefresh((value) => value + 1);

  function start() {
    if (!parent || !agentId) {
      setAgentError(t("请选择关联智能体。"));
      return;
    }
    void action.execute(async () => {
      const next = await action.mutation.run<WecomSetupJob>(base + "/jobs", {
        method: "POST",
        body: {
          enterpriseConnectionId: parent.id,
          agentId,
          versionPolicy: "latest_on_new_task",
          pinnedVersionId: null,
          name: name.trim(),
          description,
        },
      });
      setCreated(next);
      setActiveId(next.id);
      changed();
    }, "");
  }

  function change(operation: "retry" | "cancel") {
    if (!job) {
      return;
    }
    void action.execute(async () => {
      setCreated(
        await action.mutation.run<WecomSetupJob>(`${base}/jobs/${encodeURIComponent(job.id)}/${operation}`, {
          method: "POST",
          body: { expectedRevision: job.revision },
        }),
      );
      changed();
    }, "");
  }

  async function copyName() {
    if (!job) {
      return;
    }
    try {
      await navigator.clipboard.writeText(job.name);
      setCopiedName(job.name);
      setCopyError(false);
    } catch {
      setCopyError(true);
    }
  }

  if (enterpriseEditor) {
    return (
      <IntegrationEditor
        path={integrationPath(enterpriseId, system)}
        initial={null}
        mode="create"
        defaultProvider="wecom"
        quickSetup
        onClose={() => {
          setEnterpriseEditor(false);
          changed();
        }}
        onSaved={() => {
          setEnterpriseEditor(false);
          changed();
        }}
      />
    );
  }

  return (
    <Dialog title={t("快捷创建企微机器人")} icon={<IconRobot size={22} />} onClose={close} busy={action.busy}>
      <div className={styles.body}>
        <QueryState {...overview} hasData={Boolean(overview.data)} empty={t("暂时无法读取企微接入设置。")}>
          <IntegrationSetupSteps
            steps={[
              { id: "connect", label: t("企业接入") },
              { id: "create", label: t("创建机器人") },
              { id: "configure", label: t("核验并关联") },
              { id: "complete", label: t("完成") },
            ]}
            current={step}
            complete={complete}
            attention={job?.stage === "failed"}
          />
          <AnimatedHeight preserveControlShadows>
            {job ? (
              <div className={styles.progress}>
                {job.canOpen ? (
                  <section className={styles.creationPanel} aria-label={t("创建机器人")}>
                    <Image
                      className={styles.qr + " " + styles.qrCompact}
                      src={`${base}/jobs/${encodeURIComponent(job.id)}/qr`}
                      width={192}
                      height={192}
                      unoptimized
                      alt={t("企微机器人创建二维码")}
                    />
                    <div className={styles.creationDetails}>
                      <div className={styles.creationHeading}>
                        <h3 className={styles.progressTitle}>{job.name}</h3>
                        <Button type="button" className={ui.button} onClick={() => void copyName()}>
                          {copiedName === job.name ? <IconCheck size={16} /> : <IconCopy size={16} />}
                          {t(copiedName === job.name ? "已复制" : "复制名称")}
                        </Button>
                      </div>
                      <p className={styles.creationHelp}>
                        {t("使用当前企业的企微账号扫码创建，完成后在企微点击“修改”，粘贴此名称。")}
                      </p>
                      <a
                        className={ui.button}
                        href={`${base}/jobs/${encodeURIComponent(job.id)}/open`}
                        target="_blank"
                        rel="noreferrer"
                      >
                        <IconExternalLink size={17} />
                        {t("打开企微二维码页面")}
                      </a>
                      {copyError && (
                        <p role="status" className={styles.creationHelp}>
                          {t("复制失败，请选中名称后手动复制。")}
                        </p>
                      )}
                    </div>
                  </section>
                ) : (
                  <IntegrationSetupStatus name={job.name} content={wecomSetupStatus(job)} />
                )}
              </div>
            ) : !parent ? (
              <section className={styles.authorization}>
                <h3 className={styles.progressTitle}>{t("先连接企业微信")}</h3>
                <p className={styles.description}>{t("连接企业后，即可快捷创建机器人。成员账号只需绑定一次。")}</p>
                <Button type="button" className={ui.primary} onClick={() => setEnterpriseEditor(true)}>
                  <IconPlug size={17} />
                  {t("配置企业接入")}
                </Button>
              </section>
            ) : (
              <DialogForm
                id={formId}
                className={styles.form}
                onSubmit={(event) => {
                  event.preventDefault();
                  start();
                }}
              >
                {parents.length > 1 ? (
                  <Field label={t("企业应用")}>
                    <Select
                      value={parent.id}
                      disabled={action.busy}
                      onChange={(event) => setParentId(event.target.value)}
                    >
                      {parents.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </Select>
                  </Field>
                ) : (
                  <p className={styles.connectionName}>{t("企业连接：{0}", [parent.name])}</p>
                )}
                <ChatAgentPicker
                  path={path}
                  agentId={agentId}
                  versionId={agentVersion}
                  disabled={action.busy}
                  error={agentError || action.fieldErrors.agentId?.join(" ")}
                  onChange={(agent, version, label) => {
                    setAgentId(agent);
                    setAgentVersion(version);
                    setAgentError("");
                    if (!name && label) {
                      setName(label);
                    }
                  }}
                />
                <Field label={t("机器人名称")} required error={action.fieldErrors.name?.join(" ")}>
                  <Input
                    name="name"
                    required
                    maxLength={80}
                    value={name}
                    disabled={action.busy}
                    onChange={(event) => setName(event.target.value)}
                  />
                </Field>
                <Field label={t("岗位职责")} error={action.fieldErrors.description?.join(" ")}>
                  <Textarea
                    name="description"
                    maxLength={500}
                    value={description}
                    disabled={action.busy}
                    onChange={(event) => setDescription(event.target.value)}
                  />
                </Field>
              </DialogForm>
            )}
          </AnimatedHeight>
        </QueryState>
        <MutationFeedback action={action} onReload={changed} />
      </div>
      <DialogActions>
        {job?.canCancel && (
          <Button
            type="button"
            className={ui.button + " " + styles.cancelAction}
            disabled={action.busy}
            onClick={() => change("cancel")}
          >
            {t("取消创建")}
          </Button>
        )}
        <DialogCancel className={ui.button} disabled={action.busy}>
          {t(complete ? "完成" : job && !finishedWecomStages.has(job.stage) ? "稍后继续" : "关闭")}
        </DialogCancel>
        {!job && parent && (
          <Button form={formId} className={ui.primary} disabled={action.busy || !overview.data}>
            <IconRobot size={17} />
            {t("创建机器人")}
          </Button>
        )}
        {job?.canRetry && (
          <Button type="button" className={ui.primary} disabled={action.busy} onClick={() => change("retry")}>
            <IconRefresh size={17} />
            {t("重试")}
          </Button>
        )}
        {job && ["expired", "cancelled"].includes(job.stage) && (
          <Button
            type="button"
            className={ui.primary}
            onClick={() => {
              setActiveId("");
              setCreated(null);
              setCopiedName("");
            }}
          >
            {t("重新创建")}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
