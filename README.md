<p align="center">
  <img src="media/readme/cover.zh.svg" width="100%" alt="群策 AgenTeam — 多智能体协作平台" />
</p>

<p align="center">
  <strong>简体中文</strong> · <a href="README.en.md">English</a>
</p>

<p align="center">
  <a href="agenteam-web/package.json"><img src="https://img.shields.io/badge/version-0.2.0-AD6547?style=flat-square" alt="项目版本 0.2.0" /></a>
  <a href="agenteam-server/pom.xml"><img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square" alt="Java 21" /></a>
  <a href="agenteam-server/pom.xml"><img src="https://img.shields.io/badge/Spring_Boot-4-6DB33F?style=flat-square&amp;logo=springboot&amp;logoColor=white" alt="Spring Boot 4" /></a>
  <a href="agenteam-web/package.json"><img src="https://img.shields.io/badge/Next.js-16-000000?style=flat-square&amp;logo=nextdotjs&amp;logoColor=white" alt="Next.js 16" /></a>
  <a href="#quick-start"><img src="https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&amp;logo=docker&amp;logoColor=white" alt="使用 Docker Compose 部署" /></a>
</p>

<p align="center">
  <a href="#workspace">工作台</a> ·
  <a href="#conversations">对话</a> ·
  <a href="#enterprise-messaging">企业微信与飞书</a> ·
  <a href="#todos">待办</a> ·
  <a href="#schedules">定时任务</a> ·
  <a href="#quick-start"><strong>快速开始</strong></a>
</p>

AgenTeam（群策）是一个可自行部署的多智能体协作平台，支持对话任务、工具调用、文件处理、企业微信与飞书接入、待办管理和定时执行。项目包含网页端、Java 后端和容器部署配置。

本仓库为 0.2.0 社区版源码，当前拆分版本尚未正式发布。社区版只使用首次初始化创建的一个企业，成员数量不设商业上限；企业微信、飞书、通知与定时任务均为共有能力。版本边界及接口定义见 [社区版说明](docs/editions/community.md)。

智能体可以独立配置模型、技能、知识库和工具。企业成员通过数字员工使用已发布的智能体，管理员统一维护模型服务、成员角色和资源权限。

<a id="workspace"></a>

## 工作台

创建项目、选择数字员工并发起任务，支持继续历史对话及查看待办。

<a href="media/readme/screenshots/workspace.jpg"><img src="media/readme/screenshots/workspace.jpg" width="100%" alt="工作台" /></a>

<a id="conversations"></a>

## 对话

多轮对话支持工具调用、子智能体协作和人工确认。生成文件可预览、下载，并可在同一项目的多个对话中使用。

<a href="media/readme/screenshots/conversation.jpg"><img src="media/readme/screenshots/conversation.jpg" width="100%" alt="对话" /></a>

<a id="enterprise-messaging"></a>

## 企业微信与飞书

通过企业微信和飞书的企业自建应用接入，支持账号绑定、平台登录和通知发送。

- **账号绑定与登录**：成员将已有 AgenTeam 账号与企业微信或飞书账号绑定，启用后可使用绑定账号登录。
- **业务通知**：成员可选择接收任务完成、待确认、待办分配等通知，通过已绑定的企业微信或飞书账号接收消息。
- **定时通知**：按计划向指定成员发送个人通知，支持站内、企业微信和飞书渠道，并可查询逐人、逐渠道的发送结果。
- **接入管理**：管理员配置应用凭据、校验应用、发送测试通知，并分别控制账号绑定、平台登录和通知发送。

应用配置、平台授权和成员绑定步骤见 [企业消息配置说明](docs/deployment/enterprise-messaging.md)。

<a href="media/readme/screenshots/wecom-notifications.jpg"><img src="media/readme/screenshots/wecom-notifications.jpg" width="100%" alt="企业微信通知" /></a>

<a id="todos"></a>

## 待办

管理个人和团队待办，设置负责人、截止日期和优先级，跟踪事项的处理状态。

<a href="media/readme/screenshots/todos.jpg"><img src="media/readme/screenshots/todos.jpg" width="100%" alt="待办" /></a>

<a id="schedules"></a>

## 定时任务

按一次性、每日、每周或每月规则执行智能体任务或发送通知，支持执行时间预览、暂停与执行记录查询。

<a href="media/readme/screenshots/schedules.jpg"><img src="media/readme/screenshots/schedules.jpg" width="100%" alt="定时任务" /></a>

<a id="capabilities-and-management"></a>

## 能力中心与管理端

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>能力中心</h3>
      <a href="media/readme/screenshots/agents.jpg"><img src="media/readme/screenshots/agents.jpg" width="100%" alt="能力中心" /></a>
      <p>配置智能体、技能、插件、知识库和数据源，支持版本发布、智能体上架和资源授权。</p>
    </td>
    <td width="50%" valign="top">
      <h3>管理端</h3>
      <p>维护初始化企业的资料、成员与团队，分配内置角色，管理资源的企业和成员授权，配置模型服务与连接凭据，查询基础执行用量。</p>
    </td>
  </tr>
</table>

支持接入兼容 OpenAI 接口的模型服务，以及内置插件和 MCP（模型上下文协议，用于连接外部工具服务）工具。

---

<a id="quick-start"></a>

## 快速开始

使用已发布容器镜像的服务器安装方式见 [社区镜像部署说明](docs/deployment/images.md)。下面保留从源码构建的方式。

使用 Docker（容器运行工具）和 Docker Compose（多容器启动工具）部署，应用、数据库、缓存和办公沙箱会一同准备。办公沙箱是执行命令、处理文件的独立容器。

准备好 Git、Docker 和 Docker Compose；Windows 使用 Docker Desktop 的 Linux 容器模式。首次构建需要下载依赖，请确保可以访问容器镜像仓库及 Maven、npm、Python 的软件包仓库。

### 1. 获取项目

```sh
git clone https://github.com/Stonewuu/agenteam.git
cd agenteam/deploy
```

### 2. 生成配置

Linux / macOS：

```sh
sh configure.sh single
```

Windows PowerShell：

```powershell
.\configure.ps1 -Mode single
```

脚本会生成 `.env`，自动创建数据库密码、服务凭据和首次初始化凭据；已有配置不会被覆盖。

### 3. 启动服务

在 `deploy` 目录执行：

```sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 300
```

启动完成后，打开 **[http://localhost:8088](http://localhost:8088)**，按下面的顺序开始使用：

1. **初始化账号**：按页面提示完成设置，初始化凭据为 `deploy/.env` 中的 `SETUP_CREDENTIAL`。
2. **接入模型**：在“企业管理 → 模型配置”添加模型提供方、访问密钥和模型。
3. **使用智能体**：在“能力中心”创建智能体，发布并上架后，到“数字员工”中雇佣并发起对话。需要处理办公文件时，为智能体启用命令执行能力。

<details>
<summary><strong>服务器部署与常用配置</strong></summary>

默认访问地址只监听本机。对外提供服务时，在 `.env` 中设置实际访问地址和监听地址，并通过 HTTPS（加密网页连接）提供访问。完整变量见 [配置示例](deploy/.env.example)，入口配置可参考 [Nginx 反向代理示例](examples/deployment/nginx.production.example.conf)。

| 配置项                                                                  | 用途                                                                           |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `PUBLIC_URL`                                                            | 实际访问地址，用于页面链接、邮件链接及企业账号授权回调。                       |
| `BIND_ADDRESS`、`HTTP_PORT`                                             | 监听地址和端口，默认为 `127.0.0.1:8088`。                                      |
| `SPRING_PROFILES_ACTIVE`                                                | 启动环境；正式环境使用 `container,production`，并补齐所需连接及邮件配置。      |
| `MAIL_HOST`、`MAIL_PORT`、`MAIL_USERNAME`、`MAIL_PASSWORD`、`MAIL_FROM` | 邮件服务，用于邮箱验证、找回密码和邮件邀请。                                   |
| `ALLOWED_PRIVATE_ORIGINS`                                               | 需要访问的内网服务地址，例如本机模型服务 `http://host.docker.internal:11434`。 |
| `SANDBOX_MEMORY_MB`、`SANDBOX_MAXIMUM_CONCURRENT`                       | 命令执行容器的内存限制和允许同时执行的任务数。                                 |

企业微信与飞书的应用设置、成员绑定和通知配置，见 [企业消息配置说明](docs/deployment/enterprise-messaging.md)。

更新前备份数据库、配置、应用密钥和项目文件，并保留原有数据卷。已有数据库的初始化版本发生过合并时，先阅读 [数据库迁移说明](scripts/database-migration/README.md)。日常停止服务使用 `docker compose stop`；需要保留数据时，不要使用 `docker compose down -v`。

</details>

## 本地开发

前端和后端分别运行，数据库与缓存可以继续使用容器。准备 Java 21、Node.js 24、pnpm 11.15.1（前端包管理器）和 Docker；后端自带 Maven 包装脚本，无需另行安装 Maven。

<details>
<summary><strong>展开本地启动步骤</strong></summary>

**准备数据库与办公环境**

先按“快速开始”生成 `deploy/.env`，然后在 `deploy` 目录执行：

```sh
docker compose -f compose.yaml -f compose.dev.yaml up -d mysql redis
docker build -t agenteam/sandbox-office:local sandbox
```

**启动后端**

进入 `agenteam-server`，为当前终端设置以下环境变量：

| 环境变量                  | 值                                  |
| ------------------------- | ----------------------------------- |
| `AGENTEAM_DB_USERNAME`    | `agenteam`                          |
| `AGENTEAM_DB_PASSWORD`    | `deploy/.env` 中的 `DB_PASSWORD`    |
| `AGENTEAM_REDIS_PASSWORD` | `deploy/.env` 中的 `REDIS_PASSWORD` |

默认连接本机 `43306` 端口的 MySQL 和 `46379` 端口的 Redis。

```sh
./mvnw spring-boot:run
```

Windows PowerShell 使用 `.\mvnw.cmd spring-boot:run`。后端默认监听 `8080`，运行资料保存在后端目录的 `.agenteam/` 中。

**启动前端**

在另一个终端进入 `agenteam-web`：

```sh
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

打开 **[http://localhost:3000](http://localhost:3000)**。前端默认连接 `http://localhost:8080`，需要调整时设置 `AGENT_BACKEND_URL`。

</details>

<details>
<summary><strong>展开测试与构建命令</strong></summary>

后端，在 `agenteam-server` 目录执行：

```sh
./mvnw test
./mvnw package -DskipTests
```

Windows PowerShell 将 `./mvnw` 换成 `.\mvnw.cmd`。后端测试需要 Docker，会使用独立的 MySQL 和 Redis 容器；办公文档测试使用前文构建的 `agenteam/sandbox-office:local` 镜像。

对象存储测试会从固定的 MinIO 官方源码构建独立测试镜像，首次运行需要下载 Go 构建环境和依赖，后续可复用本机缓存。源码归档先核对固定摘要，原许可保留在测试镜像中；该服务不进入应用发行镜像。后端测试按类在独立 Java 进程中串行执行，每个进程最多使用 2 GB 堆内存，避免数据库映射和框架缓存随完整测试持续累积。此方式会增加完整测试耗时；GitHub 自动检查保留七天测试报告，便于排查失败。

前端，在 `agenteam-web` 目录执行：

```sh
pnpm lint
pnpm exec tsc --noEmit
pnpm build
```

按改动范围运行对应的专项测试，命令见 [package.json](agenteam-web/package.json)。

</details>

## 技术栈与目录

| 部分           | 主要技术                                                                                                                            |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 网页界面       | Next.js 16（网页应用框架）、React 19（界面组件库）、TypeScript（带类型检查的 JavaScript）与 Tailwind CSS（样式工具）。              |
| 后端服务       | Java 21、Spring Boot 4（应用框架）与 AgentScope 2（智能体开发框架）。                                                               |
| 数据存储       | MySQL 保存业务数据，Redis 保存登录会话和对话实时数据；MyBatis-Plus 及 MyBatis-Plus-Join 负责数据库访问，Flyway 负责数据库版本升级。 |
| 部署与文件处理 | Docker Compose 组织服务，Nginx 提供网页入口代理，办公沙箱执行命令及处理文档。                                                       |

```text
agenteam/
├── agenteam-web/       # 网页界面与前端业务
├── agenteam-server/    # 后端服务与工具扩展接口
├── deploy/            # 容器部署与办公沙箱镜像
├── docs/              # 部署与使用说明
├── examples/          # 代理、监控与数据库配置示例
├── scripts/           # 本机开发与数据库维护工具
└── tests/             # 部署验证脚本
```

## 参与贡献

社区源码按 [Apache-2.0](LICENSE) 提供，贡献方式与第三方来源要求见 [贡献说明](CONTRIBUTING.md)。

Copyright (c) 2026 stonewu，版权声明见 [NOTICE](NOTICE)。第三方组件保留各自的版权与许可。

欢迎通过 [问题反馈](https://github.com/Stonewuu/agenteam/issues) 提出使用建议、报告问题，或提交 Pull Request（合并请求）改进代码与文档。

- **报告问题**：提供复现步骤、预期结果和实际表现；界面问题可以附截图。
- **提出建议**：说明你想完成什么工作、目前遇到什么困难，较大的功能改动先讨论范围。
- **提交改动**：围绕一个明确的问题，写清修改原因与验证结果；涉及部署或数据库变更时补充说明。

请勿在反馈、截图或代码中附带访问密钥、账号凭据和真实业务数据；引用第三方代码时保留原有版权与许可声明。
