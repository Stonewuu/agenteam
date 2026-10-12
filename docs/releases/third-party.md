# 第三方软件与开源资料

AgenTeam 使用开源框架、语言运行环境和文件处理组件构建产品。各组件保留自身的版权和许可，配套声明与对应源码随版本提供。

## 获取许可与源码

在 [0.2.0 发布页面](https://github.com/Stonewuu/agenteam/releases/tag/v0.2.0)下载第三方源码附件。[源码索引](https://github.com/Stonewuu/agenteam/releases/download/v0.2.0/third-party-sources.json)列出组件、对应镜像、下载地址、文件大小及 SHA-256（文件内容摘要）。

附件包含对应版本的源码、发行版补丁和构建资料。下载后可以通过 `sha256sum 文件名` 核对内容摘要，并与部署版本一起保存。

## 查看镜像内的声明

| 安装内容 | 许可资料位置 |
| --- | --- |
| 后端、前端、命令执行服务及办公沙箱 | `/usr/share/licenses/agenteam/` |
| 解出的部署文件 | `licenses/agenteam/` 与 `licenses/third-party/` |

项目许可保存在 `LICENSE`，版权说明保存在 `NOTICE`。第三方原始声明通过索引关联到对应组件；使用 `docker cp` 可以从容器中取得这些文件。

## MySQL 与 Java 连接驱动

数据库服务使用官方 MySQL 8.4 镜像，也可以连接自行维护的 MySQL 实例。应用通过 MariaDB Connector/J 3.5.10（Java 数据库连接驱动）连接数据库。

该驱动采用 LGPL-2.1-or-later（GNU 宽通用公共许可证第二点一版或更新版本）。AgenTeam 的 Apache-2.0 许可与驱动原有许可分别适用。驱动原始许可、版权声明、完整对应源码和构建文件均随版本提供。

驱动作为独立文件保存在可执行包的 `BOOT-INF/lib/mariadb-java-client-3.5.10.jar` 中。使用者可以按照该驱动许可证修改、构建和替换它，以调试相关修改。准备好兼容的驱动文件后，在应用包副本上执行：

```sh
jar uf0 应用包副本.jar BOOT-INF/lib/mariadb-java-client-3.5.10.jar
```

其中 `0` 表示以未压缩形式保存嵌套库，便于 Spring Boot（应用启动与打包框架）加载。保留原始应用包，并在独立环境确认替换结果。自行修改后的包使用自己的内容摘要，与官方版本分别管理。

## 为开发者维护资料

扩展插件功能使用 PF4J 3.15.0，采用 Apache-2.0（Apache 第二版开源许可证）。它随后端依赖打包，公共接口与示例见 [扩展插件开发说明](../development/java-plugins.md)。发布时从实际构建采集 PF4J 及其依赖的许可和源码材料。

沙盒网页读取包含 Playwright 1.64.0 及配套 Chromium，网页正文解析使用 jsoup 1.22.2，Git 由镜像所属 Debian（操作系统发行版）提供。Playwright 及其浏览器安装方式见[官方浏览器文档](https://playwright.dev/docs/browsers)，jsoup 版本资料见[官方发布说明](https://jsoup.org/news/release-1.22.2)。浏览器实际版本记录在 `/opt/agenteam/browser-versions.json`，系统软件记录在 `/opt/agenteam/system-versions.txt`。发布资料必须从实际成品重新采集声明和对应源码，不能用旧镜像的材料代替。

依赖与镜像更新时，按实际组件版本整理版权原文、源码及构建资料。仓库的 `scripts/release/` 提供声明采集、源码定位、下载和归档工具；具体步骤见[第三方源码维护说明](corresponding-sources.md)。

0.3.0 开发代码采用 AgentScope Java 2.0.4，复用其官方 Responses、Anthropic 和 Gemini 扩展；对应 OpenAI Java 4.52.0、Anthropic Java 2.52.0 和 Google Gen AI 1.45.0。Responses 的客户端构造适配保存在独立目录 `agenteam-server/agenteam-agentscope-compat`，没有复制其消息或流协议实现。新版本声明须从实际构建依赖重新采集；上述 0.2.0 附件仍只对应已经发布的 0.2.0。
