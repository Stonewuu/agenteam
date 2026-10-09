# 参与 AgenTeam

**简体中文** · [English](CONTRIBUTING.en.md)

欢迎参与 AgenTeam 的开发与维护。修复问题、完善功能、改进文档和反馈使用体验，都是参与项目的方式。

本指南说明如何准备改动、完成检查并提交 Pull Request（合并请求）。

## 1. 说明问题或改进目标

先在 [Issues（问题记录）](https://github.com/Stonewuu/agenteam/issues) 中查找相关讨论。已有相同问题时，在原条目中补充信息；没有相关记录时，创建新条目说明问题或建议。

报告问题时，请提供：

- 应用版本、操作系统和部署方式。
- 能够复现问题的操作步骤。
- 预期结果和实际结果。
- 经过脱敏的错误日志或截图。

提出功能建议时，请说明使用场景、当前遇到的限制和期望的行为。涉及新功能、接口或数据库结构的改动，先在问题记录中说明目标与影响范围，再开始实现。

## 2. 准备开发环境

1. 使用 GitHub 的 Fork（将仓库复制到自己的账号）创建仓库副本，并克隆到本地。
2. 从 `main` 创建工作分支，一次合并请求围绕一个明确目标展开。
3. 按 [README 的开发与贡献章节](README.md#development) 准备运行环境、生成配置并启动服务。

项目目录按职责划分：

| 目录 | 内容 |
| --- | --- |
| `agenteam-server/` | 后端代码与测试 |
| `agenteam-web/` | 前端代码与测试 |
| `deploy/` | 部署配置、初始化工具与容器构建文件 |
| `docs/` | 部署、版本与第三方软件说明 |

## 3. 编写代码和文档

- 沿用所属模块的目录、命名和代码风格。后端业务代码按技术层和业务模块组织，前端业务代码放在对应功能目录。
- 控制语句使用大括号并换行排版。注释、日志和面向用户的错误提示使用清楚的中文，专业名称保留原文。
- 修复错误或修改业务行为时，使用现有测试或补充用例验证触发条件、预期结果和失败处理。
- 功能、配置或部署步骤变化时，同步更新相关说明和示例。涉及 README 或贡献指南时，同步维护中英文版本。
- 不提交密钥、口令、真实用户数据、本地运行配置或构建产物。日志、截图和示例先脱敏。

## 4. 检查改动

根据实际改动选择检查。纯文档修改核对内容、链接、路径和格式；代码修改执行对应测试。

在仓库根目录检查未暂存和已暂存的差异格式：

```sh
git diff --check
git diff --cached --check
```

### 后端检查

后端使用项目自带的 Maven Wrapper（固定 Maven 构建工具版本的启动脚本）。修改包结构、公共配置或核心流程时，执行完整测试；其他后端改动运行受影响的测试。

完整测试包含容器集成测试。保持 Docker（容器运行工具）运行，并先在仓库根目录构建测试所需镜像：

```sh
docker build --tag agenteam/sandbox-office:local deploy/sandbox
docker build --tag agenteam/test-minio:2025-09-07-07c3a429 agenteam-server/src/test/resources/containers/minio
```

进入 `agenteam-server/` 后运行完整测试。

Linux / macOS：

```sh
sh ./mvnw test
```

Windows PowerShell：

```powershell
.\mvnw.cmd test
```

### 前端检查

在 `agenteam-web/` 执行以下命令。首次安装或依赖变化时，先运行 `pnpm install --frozen-lockfile`，按锁文件安装依赖；pnpm（前端包管理器）的版本以 [package.json](agenteam-web/package.json) 中的 `packageManager` 为准。

```sh
pnpm lint
pnpm exec next typegen
pnpm exec tsc --noEmit
```

涉及生产路由或构建配置时，再运行生产构建：

```sh
pnpm build
```

按修改的功能运行对应专项测试，例如导航使用 `pnpm test:navigation`，企业消息入口使用 `pnpm test:channel-auth`，定时任务使用 `pnpm test:schedule-actions`。完整命令列表见 [package.json](agenteam-web/package.json)。

### 部署与自动检查

修改部署配置、构建脚本或数据库迁移时，验证对应的构建、启动或升级步骤，并说明对已有配置和数据的影响。数据库验证使用独立测试库。

仓库在创建或更新合并请求、向 `main` 推送时运行自动检查，具体步骤见 [community-checks.yml](.github/workflows/community-checks.yml)。本地检查和自动检查的失败都要定位原因；未执行的检查需在合并请求中写明原因。

## 5. 编写提交说明

提交说明遵循 [Conventional Commits 1.0.0（约定式提交）](https://www.conventionalcommits.org/en/v1.0.0/)。提交标题使用 `type: description`（类型与改动摘要）的格式，标题摘要、正文和尾部说明均使用英文准确描述实际变化；需要标明模块时，在类型后加括号。

正文和尾部说明按需要填写，标题、正文和尾部说明之间空一行。影响兼容性的变更在类型或模块名后、冒号前添加 `!`，或在尾部使用 `BREAKING CHANGE: ...`（不兼容变更说明），并写清变化与升级方式。

| 类型 | 用途 |
| --- | --- |
| `feat` | 新增功能 |
| `fix` | 修复错误 |
| `docs` | 修改文档 |
| `refactor` | 调整代码结构，保持既有行为 |
| `test` | 增加或调整测试 |
| `ci` | 修改自动检查流程 |
| `chore` | 维护构建配置、依赖或工具 |

示例：

```text
fix(auth): fix redirects after session expiration
docs: add agent-assisted deployment instructions
test: cover scheduled task execution failures
```

## 6. 提交合并请求

将工作分支推送到自己的仓库，并向 AgenTeam 的 `main` 分支发起合并请求。说明中包含：

- **改动原因**：解决的问题、使用场景及相关问题记录链接。
- **改动内容**：修改后的行为和影响范围。
- **验证结果**：实际执行的命令、结果，以及未执行检查的原因。
- **界面变化**：调整后的实际截图。
- **配置与数据变化**：部署、接口或数据库变更的操作步骤，以及对已有配置和数据的影响。

不涉及界面、配置或数据变化时，在对应项写明“不涉及”。收到审查意见后，在同一工作分支中补充修改，并更新相关验证结果。

## 许可与第三方来源

AgenTeam 使用 [Apache-2.0 开源许可证](LICENSE)。贡献的许可条件以该许可证第 5 条“提交贡献”为准。

引入第三方代码、字体、图片或依赖时，在提交说明中注明来源、版本和许可证，并按各组件的许可要求保留版权声明和许可证原文。现有组件的资料见 [第三方声明](docs/releases/third-party.md)。
