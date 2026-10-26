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
import { SettingRow } from "@/components/ui/detail-section";
import { QueryState } from "@/components/ui/query-state";
import {
  IconRobot,
  IconKey,
  IconExternalLink,
  IconRefresh,
  IconCheck,
  IconPlug,
  IconBell,
} from "@/components/ui/icons";
import { useFormAction } from "@/features/auth/hooks/use-form-action";
import { MutationFeedback } from "@/features/workspace/components/mutation-feedback";
import { useApiQuery } from "@/lib/http/use-api-query";
import { useT } from "@/lib/i18n/locale-provider";
import { chatPath } from "@/features/chat/types/chat";
import { ChatAgentPicker } from "@/features/chat/components/chat-agent-picker";
import { finishedFeishuStages, type FeishuSetupJob, type FeishuSetupOverview } from "../types/feishu-setup";
import { useFeishuAuthorization } from "../hooks/use-feishu-authorization";
import { FeishuSetupSteps } from "./feishu-setup-steps";
import { FeishuSetupStatus } from "./feishu-setup-status";
import { FeishuManagementAuthorization } from "./feishu-management-authorization";
import ui from "@/components/ui/surface.module.css";
import styles from "./feishu-quick-create.module.css";

function refreshWhileCreating(data: FeishuSetupOverview | null) {
  return data?.jobs.some(
    (job) => !finishedFeishuStages.has(job.stage) && !["failed", "authorization_required"].includes(job.stage),
  )
    ? 2500
    : 0;
}

export function FeishuQuickCreateDialog({
  enterpriseId,
  system,
  mode = "bot",
  onClose,
  onSaved,
}: {
  enterpriseId: string;
  system: boolean;
  mode?: "enterprise" | "bot";
  onClose: () => void;
  onSaved: () => void;
}) {
  const t = useT();
  const path = chatPath(enterpriseId, system);
  const base = path + "/feishu";
  const [refresh, setRefresh] = useState(0);
  const overview = useApiQuery<FeishuSetupOverview>(base, refresh, 0, false, { refreshInterval: refreshWhileCreating });
  const formId = useId();
  const [activeId, setActiveId] = useState<string | null>(null);
  const [created, setCreated] = useState<FeishuSetupJob | null>(null);
  const [managerId, setManagerId] = useState<string | null>(null);
  const [agentId, setAgentId] = useState("");
  const [agentVersion, setAgentVersion] = useState("");
  const [name, setName] = useState("");
  const [enterpriseName, setEnterpriseName] = useState(() => t("群策企业接入"));
  const [loginEnabled, setLoginEnabled] = useState<boolean | null>(null);
  const [bindingEnabled, setBindingEnabled] = useState<boolean | null>(null);
  const [messagingEnabled, setMessagingEnabled] = useState<boolean | null>(null);
  const [description, setDescription] = useState("");
  const [agentError, setAgentError] = useState("");
  const action = useFormAction();
  const authorization = useFeishuAuthorization(() => setRefresh((value) => value + 1));
  const jobs = (overview.data?.jobs ?? []).filter((job) => mode === "bot" || job.kind !== "bot");
  const resumed =
    jobs.find((job) => !finishedFeishuStages.has(job.stage) && job.stage !== "failed") ??
    jobs.find((job) => job.canRetry || job.stage === "registration_unknown");
  const chosenId = activeId === null ? resumed?.id : activeId;
  const loaded = jobs.find((job) => job.id === chosenId);
  const local = created?.id === chosenId ? created : null;
  const job = loaded && (!local || loaded.revision >= local.revision) ? loaded : local;
  const managers = overview.data?.managers ?? [];
  const connected = managers.filter((item) => item.purpose === "enterprise" && item.ready);
  const selectedId = job?.kind === "bot" ? job.managerConnectionId : (job?.connectionId ?? managerId);
  const manager =
    selectedId !== null
      ? managers.find((item) => item.connectionId === selectedId)
      : (connected.find((item) => item.authorized) ?? connected[0] ?? managers[0]);
  const enterpriseReady = manager?.purpose === "enterprise" && manager.ready;
  const complete = job?.stage === "completed";
  const showAuthorization = Boolean(
    manager?.ready &&
    !manager.authorized &&
    (job?.stage === "authorization_required" || (complete && job.kind === "enterprise") || (!job && enterpriseReady)),
  );
  const ready =
    (!job && enterpriseReady && manager.authorized) ||
    (complete && job.kind === "enterprise" && enterpriseReady && manager.authorized);
  const showEnterpriseReady = mode === "enterprise" && ready;
  const showEnterpriseForm = !job && !enterpriseReady;
  const selectedBinding = bindingEnabled ?? (manager?.purpose === "enterprise" ? manager.bindingEnabled : true);
  const selectedLogin = loginEnabled ?? (manager?.purpose === "enterprise" ? manager.loginEnabled : true);
  const selectedMessaging = messagingEnabled ?? (manager?.purpose === "enterprise" ? manager.messagingEnabled : true);

  const start = (kind: "enterprise" | "bot") =>
    void action.execute(async () => {
      if (kind === "bot" && (!agentId || !manager)) {
        setAgentError(t("请选择关联智能体。"));
        return;
      }
      const next = await action.mutation.run<FeishuSetupJob>(base + "/jobs", {
        method: "POST",
        body: {
          kind,
          managerConnectionId: kind === "bot" ? manager!.connectionId : null,
          connectionId: kind === "enterprise" ? (manager?.connectionId ?? null) : null,
          agentId: kind === "bot" ? agentId : null,
          versionPolicy: "latest_on_new_task",
          pinnedVersionId: null,
          name: kind === "bot" ? name.trim() : (manager?.name ?? enterpriseName.trim()),
          description: kind === "bot" ? description : t("用于企业账号、通知与岗位机器人管理"),
          loginEnabled: kind === "enterprise" ? selectedLogin : null,
          messagingEnabled: kind === "enterprise" ? selectedMessaging : null,
          ...(kind === "enterprise" ? {bindingEnabled: selectedBinding} : {}),
        },
      });
      setCreated(next);
      setActiveId(next.id);
      setRefresh((value) => value + 1);
    }, "");
  const authorize = () => {
    if (!manager) {
      return;
    }
    void action.execute(
      () =>
        authorization.open(manager.connectionId, () =>
          action.mutation.run<{ authorizationUrl: string }>(
            base + "/managers/" + encodeURIComponent(manager.connectionId) + "/authorize",
            { method: "POST", body: {} },
          ),
        ),
      "",
    );
  };
  const change = (operation: "retry" | "cancel") => {
    if (!job) {
      return;
    }
    void action.execute(async () => {
      setCreated(
        await action.mutation.run<FeishuSetupJob>(base + "/jobs/" + encodeURIComponent(job.id) + "/" + operation, {
          method: "POST",
          body: { expectedRevision: job.revision },
        }),
      );
      setRefresh((value) => value + 1);
    }, "");
  };
  const continueCreating = () => {
    if (job && job.kind !== "bot" && job.connectionId) {
      setManagerId(job.connectionId);
    }
    setActiveId("");
    setCreated(null);
    setRefresh((value) => value + 1);
  };
  const close = complete || showEnterpriseReady ? onSaved : onClose;
  const creationUrl = job ? base + "/jobs/" + encodeURIComponent(job.id) + "/open" : "";

  return (
    <Dialog
      title={t(mode === "enterprise" ? "快捷配置飞书企业接入" : "快捷创建飞书机器人")}
      icon={mode === "enterprise" ? <IconPlug size={22} /> : <IconRobot size={22} />}
      onClose={close}
      busy={action.busy}
    >
      <div className={styles.body}>
        <QueryState {...overview} hasData={Boolean(overview.data)} empty={t("暂时无法读取飞书接入设置。")}>
          <FeishuSetupSteps job={job} manager={enterpriseReady ? manager : undefined} mode={mode} />
          <AnimatedHeight preserveControlShadows>
            {showAuthorization ? (
              <FeishuManagementAuthorization
                name={manager!.name}
                busy={action.busy}
                onAuthorize={authorize}
                fallbackUrl={
                  authorization.fallback?.connectionId === manager!.connectionId
                    ? authorization.fallback.url
                    : undefined
                }
              />
            ) : showEnterpriseReady ? (
              <section className={styles.authorization}>
                <span className={styles.readyIcon} data-icon-context="inherit">
                  <IconCheck size={48} />
                </span>
                <h3 className={styles.progressTitle}>{t("企业接入已完成")}</h3>
                <p className={styles.description}>{manager!.name}</p>
                <p className={styles.confirmationHint}>{t("后续创建岗位机器人时，将复用此企业连接和你的管理授权。")}</p>
              </section>
            ) : job ? (
              <div className={styles.progress}>
                <FeishuSetupStatus job={job} />
                {job.canOpen && (
                  <div className={styles.confirmation}>
                    <div className={styles.creationChoices}>
                      <div className={styles.scan}>
                        <Image
                          className={styles.qr}
                          src={base + "/jobs/" + encodeURIComponent(job.id) + "/qr"}
                          width={224}
                          height={224}
                          unoptimized
                          alt={t("飞书应用创建二维码")}
                        />
                        <p className={styles.description}>{t("用飞书扫码")}</p>
                      </div>
                      <div className={styles.separator} aria-hidden="true">
                        {t("或")}
                      </div>
                      <div className={styles.openLink}>
                        <a className={ui.primary} href={creationUrl} target="_blank" rel="noreferrer">
                          <IconExternalLink size={17} />
                          {t("打开飞书创建页面")}
                        </a>
                        <p className={styles.description}>{t("在浏览器中确认创建")}</p>
                      </div>
                    </div>
                    <p className={styles.confirmationHint}>{t("任选一种方式确认，群策会自动完成配置和发布。")}</p>
                  </div>
                )}
              </div>
            ) : showEnterpriseForm ? (
              <DialogForm
                id={formId}
                className={styles.form}
                onSubmit={(event) => {
                  event.preventDefault();
                  start("enterprise");
                }}
              >
                <div className={styles.introduction}>
                  <h3 className={styles.progressTitle}>{t("连接企业飞书")}</h3>
                  <p className={styles.description}>
                    {t("先连接企业账号，后续岗位机器人都可以复用这份连接快捷创建。")}
                  </p>
                </div>
                {managers.length > 0 && (
                  <Field label={t("企业应用")}>
                    <Select
                      value={manager?.connectionId ?? ""}
                      disabled={action.busy}
                      onChange={(event) => {
                        setManagerId(event.target.value);
                        setLoginEnabled(null);
                        setBindingEnabled(null);
                        setMessagingEnabled(null);
                      }}
                    >
                      {managers.map((item) => (
                        <option key={item.connectionId} value={item.connectionId}>
                          {item.name}
                        </option>
                      ))}
                      <option value="">{t("新建飞书企业应用")}</option>
                    </Select>
                  </Field>
                )}
                {!manager && (
                  <Field label={t("应用名称")} required error={action.fieldErrors.name?.join(" ")}>
                    <Input
                      name="name"
                      value={enterpriseName}
                      required
                      maxLength={80}
                      disabled={action.busy}
                      onChange={(event) => {
                        setEnterpriseName(event.target.value);
                        action.clearFieldError("name");
                      }}
                    />
                  </Field>
                )}
                {manager && (
                  <p className={styles.confirmationHint}>{t("将使用已有应用完成企业接入，无需再次创建。")}</p>
                )}
                <SettingRow icon={<IconPlug size={18} />} label={t("允许成员绑定账号")} description={t("成员可关联当前企业的渠道账号。")}
                  checked={selectedBinding} disabled={action.busy} onChange={setBindingEnabled}/>
                <SettingRow
                  icon={<IconKey size={18} />}
                  label={t("允许已绑定成员登录")}
                  description={t("成员可通过此企业账号登录。")}
                  checked={selectedLogin}
                  disabled={action.busy}
                  onChange={setLoginEnabled}
                />
                <SettingRow
                  icon={<IconBell size={18} />}
                  label={t("允许发送通知")}
                  description={t("向已绑定且允许接收的成员发送工作通知。")}
                  checked={selectedMessaging}
                  disabled={action.busy}
                  onChange={setMessagingEnabled}
                />
              </DialogForm>
            ) : (
              <DialogForm
                id={formId}
                className={styles.form}
                onSubmit={(event) => {
                  event.preventDefault();
                  start("bot");
                }}
              >
                {connected.length > 1 ? (
                  <Field label={t("企业应用")}>
                    <Select
                      value={manager?.connectionId ?? ""}
                      disabled={action.busy}
                      onChange={(event) => setManagerId(event.target.value)}
                    >
                      {connected.map((item) => (
                        <option key={item.connectionId} value={item.connectionId}>
                          {item.name}
                        </option>
                      ))}
                    </Select>
                  </Field>
                ) : (
                  <p className={styles.connectionName}>{t("企业连接：{0}", [manager?.name ?? ""])}</p>
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
                <Field label={t("岗位名称")} required error={action.fieldErrors.name?.join(" ")}>
                  <Input
                    required
                    name="name"
                    value={name}
                    maxLength={80}
                    disabled={action.busy}
                    onChange={(event) => setName(event.target.value)}
                  />
                </Field>
                <Field label={t("岗位职责")} error={action.fieldErrors.description?.join(" ")}>
                  <Textarea
                    name="description"
                    value={description}
                    maxLength={500}
                    disabled={action.busy}
                    onChange={(event) => setDescription(event.target.value)}
                  />
                </Field>
              </DialogForm>
            )}
          </AnimatedHeight>
        </QueryState>
        <MutationFeedback action={action} onReload={() => setRefresh((value) => value + 1)} />
      </div>
      <DialogActions>
        {job?.canCancel && (
          <div className={styles.cancelAction}>
            <Button
              type="button"
              className={ui.button}
              disabled={action.busy}
              title={t("取消流程后，已经创建的飞书应用仍会保留。")}
              onClick={() => change("cancel")}
            >
              {t("取消创建")}
            </Button>
          </div>
        )}
        {!showEnterpriseReady && !(complete && job.kind === "bot") && (
          <DialogCancel className={ui.button} disabled={action.busy}>
            {t((job && !finishedFeishuStages.has(job.stage)) || showAuthorization ? "稍后继续" : "关闭")}
          </DialogCancel>
        )}
        {showEnterpriseReady ? (
          <Button className={ui.primary} onClick={onSaved}>
            <IconCheck size={17} />
            {t("完成")}
          </Button>
        ) : job ? (
          <>
            {job.canRetry && !showAuthorization && (
              <Button type="button" className={ui.primary} disabled={action.busy} onClick={() => change("retry")}>
                <IconRefresh size={17} />
                {t("继续处理")}
              </Button>
            )}
            {complete && !showAuthorization && (
              <Button type="button" className={ui.primary} onClick={job.kind === "bot" ? onSaved : continueCreating}>
                {t(job.kind === "bot" ? "完成" : "继续创建机器人")}
              </Button>
            )}
            {["cancelled", "expired", "registration_unknown"].includes(job.stage) && (
              <Button type="button" className={ui.primary} onClick={continueCreating}>
                {t("返回填写")}
              </Button>
            )}
          </>
        ) : (
          overview.data &&
          !showAuthorization && (
            <Button
              form={formId}
              type="submit"
              className={ui.primary}
              disabled={action.busy || (!showEnterpriseForm && !agentId)}
            >
              {showEnterpriseForm ? <IconPlug size={17} /> : <IconRobot size={17} />}
              {t(showEnterpriseForm ? (manager ? "开始快捷配置" : "连接飞书") : "创建飞书机器人")}
            </Button>
          )
        )}
      </DialogActions>
    </Dialog>
  );
}
