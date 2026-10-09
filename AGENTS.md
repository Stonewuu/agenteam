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
