# 自动发布项目镜像

发布正式 GitHub Release（版本发布页面）后，`Community images` 工作流自动检查源码、构建五类镜像并推送对应版本。固定版本全部确认后，保存社区版本清单，再将 `latest`（最新正式版标签）更新为同一组镜像。安装用户无需构建，在部署目录执行 `docker compose pull` 和 `docker compose up -d` 即可更新。

## 一次性配置

在仓库的 Settings → Secrets and variables → Actions 中配置：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret（加密配置） | `DOCKERHUB_TOKEN` | Docker Hub 访问令牌，权限选择 Repo Read & Write |
| Variable（普通配置） | `DOCKERHUB_USERNAME` | 拥有项目镜像推送权限的 Docker 用户名 |

令牌只由登录镜像仓库的步骤使用，不写入源码、构建参数或发布附件。工作流只检出本仓库，镜像输入由已有文件清单生成；原许可证和版权声明保留。

## 发布版本

1. 同步后端、前端及 `deploy/registry/release.env` 的版本号并提交代码。
2. 在该提交上创建 `vX.Y.Z` 标签，发布同名正式 Release。草稿和预发布不会推送正式镜像。
3. 在 Actions 中查看 `Community images`。前后端完整检查通过后，五类镜像分别构建，检查运行环境、版本和版权文件，再推送独立构建标签。
4. 全部成功后，工作流发布固定版本标签，向对应 Release 上传只含本项目来源和镜像摘要的 `release-batch.json`，最后更新 `latest`。如果更高版本已完成镜像发布，较旧版本只补齐自己的固定标签；仍在排队构建的新 Release 不会阻断当前更新。

一次发布一个正式版本，发布工作流串行执行。固定版本已有不同内容时会停止，不覆盖已发布镜像。不要移动已发布源码标签或删除正式清单来重新构建。

通过 GitHub 页面或维护者的 GitHub CLI（命令行工具）发布可触发此工作流。其他自动化若使用默认 `GITHUB_TOKEN` 创建 Release，GitHub 通常不会继续触发另一个工作流；需要使用具有对应权限的 GitHub App（应用身份）或个人访问令牌。[GitHub 触发规则](https://docs.github.com/en/actions/how-tos/writing-workflows/choosing-when-your-workflow-runs/triggering-a-workflow)

## 验证与失败重试

在 Actions → Community images → Run workflow 中，保持 `publish` 关闭、`release_tag` 留空，可以验证当前分支的完整构建及推送权限。验证只推送 `build-<运行编号>-<尝试编号>`，不改固定版本、`latest` 或 Release 附件。

发布中断后优先选择 Re-run failed jobs（重跑失败任务），继续使用同一次运行已经构建的镜像结果。手工继续正式发布时，工作流分支选择对应版本标签，`release_tag` 填写同一标签并开启 `publish`；源码版本、标签和实际提交必须相同。已有固定标签或清单发生冲突时，应核对原运行记录，不重新覆盖。

程序包、报告和镜像结果在 Actions 中保留七天。只有正式版本和 `latest` 完成确认，才表示镜像发布完成；Release 页面创建成功本身不代表镜像已经可用。
