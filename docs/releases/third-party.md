# 准备第三方声明资料

前端构建会把部分依赖合并到网页文件中，运行镜像也会省略部分依赖目录和许可文件。因此，仅扫描最终目录或记录许可证名称，会漏掉需要保留的原始资料。发布前先保存实际构建依赖和运行产物的清单，再按对应版本补充原文，最后把审核后的文件交给镜像和安装包。

## 从实际产物收集

- 后端使用 `scripts/release/collect-java-licenses.py`，读取真实可执行 JAR（Java 打包文件），按文件摘要核对本地 Maven（Java 构建与依赖管理工具）描述，并保存包内声明。编译后的 License.class 或 Notice.class 是程序类，不是许可文件。
- 前端使用 `scripts/release/collect-node-licenses.mjs`，在实际 Linux 构建阶段的依赖目录中执行。保留已安装生产依赖、对等依赖及带许可章节的 README。最终 standalone（只保留运行所需文件的前端目录）检查另存，不能用其缺少目录的结果替代完整构建清单。
- 办公镜像使用 `scripts/release/collect-office-licenses.py` 和 Node 收集工具，保存实际系统包、Python 包及 Node 包原声明。前端和后端基础镜像、Java 与 Node 运行环境及原生组件仍须分别核对，不能由办公镜像清单代替。

缺少原文时，`scripts/release/collect-java-source-licenses.py` 可以按实际组件编号从 Maven Central（Java 公共依赖仓库）取得同版本源码包。一个打包文件合并了其他组件时，各组件分别登记。源码文件头中的声明是补充依据，不代表上游没有另行提供 NOTICE（原始归属声明）或其他分发要求。

基础镜像另用 `scripts/release/collect-image-runtime-sources.py --image <本地镜像编号> --output <新目录>` 采集。工具固定实际镜像编号，在断网、文件系统只读的临时容器内读取已安装系统包、源码包名称和版本、运行环境版本及原文，完成后删除该临时容器。输出保留原始归档与摘要，不在宿主机解压其中的路径。无法按 UTF-8（文本字符编码）读取的文件以 Base64（将原始字节表示为文本的方式）保存，避免替换字符改变原文。

后端、执行器、前端和办公镜像分别采集，不能只采其中一种。系统包表不会包含单独复制的 Java、Node、Python 或 Docker 客户端，因此还要使用报告中的运行环境版本、二进制摘要以及 Java release（构建版本记录）文件，定位它们的源码和构建脚本。`sourcePackages` 只列出实际需要的源码包名称和版本，不表示这些源码已经下载或提供给客户。

补充文件使用对应版本的上游提交，保存原文、来源地址及 SHA-256（文件内容摘要）。不要用当前主分支覆盖历史版本，也不要为上游没有署名的包填写猜测的版权人。

## 整理独立原文和索引

`scripts/release/assemble-third-party-documents.py` 接收实际报告、源码补充报告以及人工核对的上游文件清单，输出 README.md、index.json 和 notices 目录。例如：

```sh
python scripts/release/assemble-third-party-documents.py \
  --java-report 后端报告.json \
  --node-report frontend=前端报告.json \
  --node-report office-node=办公Node报告.json \
  --office-report 办公系统报告.json \
  --java-source-report 源码补充报告.json \
  --supplements 上游补充清单.json \
  --output 新的资料目录
```

工具检查原文字节与摘要，合并相同原文，但保持每个组件的关联关系。输出目录必须是新目录，不覆盖已有资料。上游补充文件只允许来自补充清单所在目录的内部路径。

新报告同时保存实际后端文件摘要，以及前端和办公依赖锁文件的摘要。更新采集工具前生成的旧报告缺少这些信息时，应重新采集；不要手工填写一个摘要来冒充来自该次构建的报告。

`withoutAttributions` 表示没有取得任何原始资料的组件；`metadataOnly` 表示只取得了上游包描述中的许可字段。两者含义不同，都不能靠填入通用版权人来消除。`releaseApproved` 固定为 false：这套工具整理材料，不替代正式发布检查。

## 与本次发布核对

构建或依赖变化后，重新核对实际组件版本和内容摘要。正式资料还须明确适用的镜像、原生组件和基础运行环境、应提供的对应源码及取得方式，并保留第三方原有条款。审核通过后再随正式产物分发；不能仅凭资料条目数量或没有缺少项就登记许可检查通过。

独立社区镜像准备使用 `--third-party-documents <资料目录>`。两版协调入口同时使用 `--community-third-party-documents <社区资料目录>` 和 `--pro-third-party-documents <商业资料目录>`，各自绑定本版后端；办公镜像共用社区资料，以保持相同的构建输入。正式准备缺少资料时会停止，开发准备仍允许仅附项目本身的许可。

独立社区正式流程先在干净提交中执行 `node scripts/release/build-community-backend.mjs --release`。该命令实际执行后端测试和打包，生成 `.build/backend/build-inputs.json`，记录提交、版本、测试执行情况及普通包和可执行包摘要。用其中的可执行包收集第三方资料，再执行 `node scripts/release/prepare-community-images.mjs --release --third-party-documents <资料目录>`。正式准备核对构建记录，不能把同版本的旧后端文件标成当前提交。

本地验证可使用 `--skip-tests --local-cache` 构建，但记录会保留开发状态，不能用于正式准备。开发准备也可以明确传入 `--backend <可执行包>`；正式模式下，该文件仍须与当前正式构建记录逐字节相同。

准备过程核对原文、目录内容、实际后端及依赖锁文件，再把明确列出的文件复制进本批构建目录。后端、前端、执行器和办公镜像保存到 `/usr/share/licenses/agenteam/`，部署镜像保存到 `/deployment/licenses/`。正式许可检查仍由发布流程根据实际条款、源码安排及最终镜像完成，准备脚本不会因此批准发布。

实际运行环境和原生组件的对应源码按[保存与成品对应的第三方源码](corresponding-sources.md)整理。源码文件通常大于声明文件，作为独立版本附件保存和交付，并记录它们与镜像的对应关系。

## MariaDB 连接驱动的资料

后端使用未经修改的 MariaDB Connector/J 3.5.10，其许可为 LGPL-2.1-or-later（GNU 宽通用公共许可证第二点一版或更新版本）。数据库服务仍由官方 MySQL 镜像或用户提供的 MySQL 实例运行。[驱动官方说明](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j)列明 MySQL 兼容范围；项目的 Apache 许可和商业扩展条款不替代驱动原有许可。

每次交付保留驱动原始许可、版权声明和对应版本完整源码及构建文件；对应源码进入同版本第三方源码附件。最终可执行包应只包含选定的 MariaDB 驱动，不能在替换后仍附带旧 mysql-connector-j。

驱动在可执行包内保持独立的 `BOOT-INF/lib/mariadb-java-client-3.5.10.jar`，不合并或混淆到项目类中。使用者可以修改、重新构建并替换这个库，以调试其修改；商业扩展条款不得取消驱动许可赋予的这些权利。替换时保留该路径和文件名，将兼容的新驱动放入工作目录对应位置，再对应用包副本执行 `jar uf0 应用包副本.jar BOOT-INF/lib/mariadb-java-client-3.5.10.jar`。其中 `0` 要求不压缩嵌套库，以便 Spring Boot（应用启动与打包框架）正常加载；保留原包，并在独立环境验证后使用。修改后的应用包不再对应官方发布摘要。
