---
name: agenteam-check
description: "对 AgenTeam 仓库的改动执行与影响范围相符的提交前或合并前检查，核对版本、数据库迁移、构建输入和公开资料。用于检查当前改动或确认合并准备情况；不自动提交、发布或部署。"
---

# 检查 AgenTeam 改动

先确定改动影响，再选择检查。目标是用可复现的结果说明这次改动能否合并，避免为纯文档变更重复构建全部应用。

所有下列路径从本仓库根目录计算。本技能可在单独克隆的 AgenTeam 仓库中使用。

## 读取实际改动

- 核对 Git 根目录、目标分支、已提交差异与未提交差异，保留用户其他工作。读取适用的仓库规则和 `.github/workflows/community-checks.yml`。
- 仓库存在可用 `.codegraph/` 时，理解或定位代码先用 `codegraph explore`；未索引的脚本和配置直接读取，不自行建立索引。
- 检查使用当前前后端配置要求的 Java、Node 和 pnpm 版本；按 `agenteam-web/package.json` 的 `packageManager`（固定包管理器版本）准备工具，不借检查顺带升级依赖。
- 已有相同改动、相同环境的有效结果可直接引用。提交前的差异变化、合并冲突或构建输入变化时，重新检查受影响部分。

## 按影响选择检查

| 改动范围 | 必要检查 |
| --- | --- |
| 文案、文档、开发技能 | 内容真实性、链接与路径、文件格式、`git diff --check`；技能额外检查其元数据与调用范围 |
| 应用版本字段 | 核对前后端及 `deploy/registry/release.env` 一致，五类产品镜像引用使用同版本；不改第三方版本 |
| 前端代码 | `pnpm lint`、`pnpm exec next typegen`、`pnpm exec tsc --noEmit`；涉及生产路由或构建配置再执行 `pnpm build`，按实际行为选择现有专项测试 |
| 后端代码 | 选择受影响的现有单元或集成测试；修改包结构、公共配置或核心流程时执行完整 `./mvnw.cmd test`，并遵守适用规则 |
| 数据库迁移 | 核对新脚本命名和顺序、空库结构、已有数据升级及重复启动；使用独立测试数据库 |
| 打包、部署文件或依赖 | 检查实际构建上下文、产物、依赖锁文件和对应声明；修改脚本时执行对应的现有脚本测试 |

前端命令在 `agenteam-web/` 执行，后端命令在 `agenteam-server/` 执行；非 Windows 系统使用 `sh ./mvnw test`。首次使用或依赖改变时执行 `pnpm install --frozen-lockfile`（严格使用锁文件安装依赖）。不要在每次复查时重复安装。

仅修改版本字段时，可在仓库根目录执行只读检查：

```powershell
node --input-type=module -e 'import {readCommunitySource} from "./scripts/release/community-backend.mjs"; const s = readCommunitySource(); console.log(s.version, s.commit);'
```

需要独立后端构建时，根目录的 `node scripts/release/build-community-backend.mjs --local-cache` 使用本地依赖缓存并执行测试。它是完整构建，不作为每次文档检查的默认步骤。正式构建的要求以发布任务为准，不能把跳过测试的产物当作验证通过。

专项测试从 `agenteam-web/package.json` 中选择。例如导航对应 `pnpm test:navigation`，企业通信入口对应 `pnpm test:channel-auth`，定时操作对应 `pnpm test:schedule-actions`。先确认用例确实覆盖本次行为；不要为了数量新增只匹配源码文字的测试。

## 数据与公开资料

- Flyway（按版本执行数据库迁移脚本的工具）的文件在 `agenteam-server/src/main/resources/db/schema/`。当前应用版本取 `deploy/registry/release.env` 的 `RELEASE_TAG`；新文件使用 `V<版本>.<序号>__简短中文说明.sql`。已发布或已执行文件保持原名和内容，不自动清库、修复历史或关闭校验。
- 提交只包含任务需要的源码、文档和配置示例。检查新增文件与提交差异，排除令牌、私钥、真实账号、运行数据、个人绝对路径及本机构建产物；环境变量示例使用明确占位内容。
- 对外文案以真实能力、体验变化和使用步骤为主，保留必要安装要求、数据保护步骤和第三方许可资料。
- 依赖或镜像更新时参考 [第三方软件说明](../../../docs/releases/third-party.md) 与 [源码维护说明](../../../docs/releases/corresponding-sources.md)，确认资料和实际产物一致。

## 交付检查结论

列出已执行的命令、实际结果、已复用的结果及仍未检查的具体事项。运行失败或环境不具备时记录真实原因，不把“未运行”计为通过。检查完成不自动创建提交、合并请求、版本标签或部署任务。
