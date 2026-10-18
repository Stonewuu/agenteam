# AgenTeam 仓库规则

## 提交说明

本规则适用于本仓库及其子目录。统一提交格式，便于识别每次改动的目的和影响。

- 所有新建或改写的提交说明遵循 [Conventional Commits 1.0.0（约定式提交）](https://www.conventionalcommits.org/en/v1.0.0/)。标题摘要、正文和尾部说明统一使用英文。
- 标题使用 `type: description`（类型与改动摘要）或 `type(scope): description`（增加模块名），冒号后保留一个空格。模块名按实际修改范围填写，例如 `auth`、`agent`；不需要区分模块时省略括号及模块名。
- 新增功能使用 `feat`，修复错误使用 `fix`；文档、重构、测试、自动检查和日常维护分别使用 `docs`、`refactor`、`test`、`ci`、`chore`。
- 正文和尾部说明按需要填写，标题、正文和尾部说明之间空一行。影响兼容性的变更必须在类型或模块名后、冒号前添加 `!`，或在尾部使用 `BREAKING CHANGE: ...`（不兼容变更说明），并写清变化与升级方式。
- 创建或修订提交后，使用 `git show -s --format=fuller <提交>` 核对实际提交说明；一次生成多个提交时逐条检查。

示例：

```text
fix(auth): fix redirects after session expiration
docs: add agent-assisted deployment instructions
test: cover scheduled task execution failures
```

贡献流程和检查命令见 [中文贡献指南](CONTRIBUTING.md) 与 [英文贡献指南](CONTRIBUTING.en.md)。

## 测试与运行临时输出

- 后端测试通过 `TestOutputPaths` 和 `agenteam.test.artifacts-root` 统一定位测试数据、工作区、快照与子进程日志，不再写死 `target/<测试名称>`。Maven 测试报告由 `agenteam.test.reports` 指定；独立执行默认在仓库 `.runtime/tests/` 下，父工作区执行时显式传入父目录中的绝对路径。正式构建的归档证据保留在构建目录中。

开发、测试和临时服务的日志、报告、截图及启动信息统一放在 `.runtime/<任务>/`，禁止散放项目根目录；位于 agenteam-package 工作区时使用父目录的 `.runtime/`。运行前指定输出目录并保持 Git 忽略，停止本任务进程后再清理对应子目录。标准编译缓存和依赖继续使用工具既有目录，不把真实业务数据放入临时输出目录。

## 数据库迁移版本

- Flyway（数据库迁移管理工具）的新增脚本只能使用当前尚未发布的开发版本，并与应用版本及 `deploy/registry/release.env` 保持一致。
- 新增前检查 GitHub Release（版本发布页面）、发布标签与完成记录。某版本发布后，该版本及之前的迁移序列冻结，不得追加脚本，也不得修改、删除、重命名或重新编号已有文件；不能因本地版本配置尚未更新而沿用已发布版本。
- 例如 0.3.0 已发布而当前开发 0.4.0 时，新脚本只能使用 `V0.4.0.<序号>__简短中文说明.sql`。按当前开发版本内的最大序号加一；组合构建时同时核对相邻商业仓库的序号。
- 只有用户明确授权并独立发布的 baseline（数据库基线脚本）可以在已确认范围内整理历史，同时必须说明空库初始化和已有库升级方式并验证。普通迁移或单独改动 `V0__init.sql` 不属于此例外，不能自动修复历史或关闭校验。
