# 开发与安装扩展插件

AgenTeam 使用 PF4J（Java 动态插件框架）加载开发者提供的工具实现。平台管理员上传编译好的 JAR（Java 程序包）并确认安装后，可以在插件配置页填写业务配置、关联凭据和选择工具，再将发布版本交给智能体或工作流使用。

插件代码在独立 Java 进程中执行。升级和卸载立即使旧安装版本失效，停止旧版本调用并删除数据库中的旧程序包。已有智能体配置保留旧引用，提示维护者重新选择；平台不会自动把旧调用转向新版。已提交到外部系统的操作不能随取消撤销。

## 开发环境与公共接口

使用 Java 21。平台提供两个独立依赖包：

- `com.stonewu:agenteam-tool-api:1.0.0`：工具定义与调用上下文。
- `com.stonewu:agenteam-plugin-api:1.0.0`：`JavaToolPlugin`（Java 插件扩展接口）和 `JavaPluginContext`（调用身份及凭据接口）。

插件工程还需要 `org.pf4j:pf4j:3.15.0`。以上三个依赖都设置 `provided`（由平台运行环境提供），不要把另一份接口类或 PF4J 实现打进插件包。插件自己的其他依赖可打入普通 JAR；不能使用 Spring Boot（应用启动与打包框架）的嵌套可执行包结构。

源码中的完整示例在 `agenteam-server/agenteam-plugin-api/examples/echo-plugin/`。从 `agenteam-server/` 构建：

```powershell
./mvnw.cmd -f agenteam-tool-api/pom.xml install
./mvnw.cmd -f agenteam-plugin-api/pom.xml install
./mvnw.cmd -f agenteam-plugin-api/examples/echo-plugin/pom.xml package
```

产物为示例目录 `target/agenteam-echo-plugin-1.0.0.jar`。验证升级时可使用 `-Drevision=1.1.0` 构建新版本。

需要体验实际功能时，使用 [JSON 数据助手](../../agenteam-server/agenteam-plugin-api/examples/json-tools-plugin/README.md)。它提供校验、格式化、字段提取和结构比较四个工具，不需要凭据；可在配置页调整缩进、字段排序和差异数量。执行 `./mvnw.cmd -f agenteam-plugin-api/examples/json-tools-plugin/pom.xml clean verify` 构建，上传该示例目录下的 `target/agenteam-json-tools-plugin-1.0.0.jar`。

## 包结构与说明

普通插件 JAR 必须包含编译后的扩展类、`META-INF/extensions.idx`（PF4J 扩展索引）、`META-INF/MANIFEST.MF`（程序包清单）和 `META-INF/agenteam-plugin.json`（平台配置声明）。示例工程已配置编译期注解处理，使用 `@Extension` 标记实现类后自动生成扩展索引。

每个包只能提供一个 `JavaToolPlugin` 实现，该实现可以声明 1 至 100 个工具。首期不支持包与包之间的运行依赖。

清单字段如下：

| 字段 | 含义与要求 |
| --- | --- |
| `Plugin-Id` | 稳定插件标识，最长 80 字符，由字母、数字、点、下划线和短横线组成 |
| `Plugin-Version` | 插件版本，例如 `1.0.0`；当前已安装版本不能被相同版本号覆盖 |
| `Plugin-Requires` | 公共插件接口版本，首期固定为 `1.0.0` |
| `Plugin-Description` | 给使用者看的简介，必填，最长 2000 字符 |
| `Plugin-Provider` | 作者或维护组织，必填，最长 100 字符 |
| `Plugin-License` | 插件使用许可，建议明确填写 |

`agenteam-plugin.json` 的例子：

```json
{
  "name": "文本格式示例",
  "configurationSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {
      "prefix": {
        "type": "string",
        "title": "文本前缀",
        "description": "每次返回文本前附加的内容。",
        "default": "示例：",
        "maxLength": 100
      }
    },
    "required": ["prefix"]
  }
}
```

`configurationSchema` 使用 JSON Schema（描述字段类型和校验要求的结构）。首期支持最多 30 个平铺字段，类型为文本、整数、数值或布尔值；可设置枚举、必填项和范围约束。字段名以字母开头，只含字母、数字和下划线，最长 64 字符。字段标题必填。嵌套对象和数组不属于首期配置表单范围。

给模型看的说明放在每个 `ToolSpec`（工具定义）的 `description` 中，写明用途、适用条件、参数和限制。平台只把选择并发布的工具提供给模型。完整开发文档和维护者说明不自动进入模型上下文。

## 工具接口约定

`tools()` 返回稳定的工具清单，不能读取安装凭据或执行外部修改。参数结构使用 JSON Schema，工具名称最长 128 字符，允许字母、数字、点、下划线和短横线，同一包内不能重复。等待上限为 1 至 120 秒，实际执行还受任务剩余时间限制。

`operationClass`（工具操作类别）必须如实填写为 `read`（读取）、`write`（写入）、`destructive`（删除等破坏性修改）或 `unknown`（无法确定）。平台沿用现有授权与人工确认流程；工具声明本身不会授予访问业务资源的权限。

`invoke(tool, arguments, configuration, context)` 接收工具名称、模型参数、安装配置及当前调用上下文。返回值为结构化对象。可用 `content` 表达结果，并通过 `isError: true` 明确报告业务失败；不要把内部异常堆栈作为工具结果返回。

- `context.enterpriseId()` 和 `context.actorId()` 提供平台核定的身份，不从模型参数接收身份覆盖。
- `context.credentialHeaders()` 提供本次关联凭据对应的请求头，仅供访问明确授权的目标服务。不得把凭据写入配置、日志或返回内容。
- `context.beforeSend()` 在外部操作前向主服务重新核对版本、权限、确认和取消状态。一次工具调用中的多个业务请求可以分别调用它，不会被当作重试整个工具。
- `context.cancellation().requireActive()` 可在循环中主动检查取消或期限。
- `context.cancellation().track(resource)` 登记本次调用结束时需要关闭的资源。

首期不支持插件自行声明重复写入去重或结果查询能力，`supportsDeduplication` 和 `supportsResultQuery` 必须为 `false`。平台通过结束执行进程支持取消。写操作已经提交但结果未知时，平台不会自动重新提交。

插件实例按本次执行创建，不能依赖静态变量在多次调用之间保存数据。需要长期保存的数据应由作者自己的业务服务管理；不得保留超出本次调用的线程或子进程。插件启动、工具发现和停止方法也必须遵守这一约定。

## 安装与升级

在插件配置页选择“安装扩展插件”，平台管理员可以上传程序包。上传检查成功后会展示真实包信息与工具说明；确认安装才替换活动版本。检查失败或取消安装不影响原版本。

智能体已选插件及插件选择弹窗会按所选发布版本显示“内置插件”“远程插件”或“扩展插件”。界面中的“扩展插件”对应本页介绍的 Java 实现；一个配置引用多种来源时，分别显示实际包含的类型。

同一个企业中每个插件标识只保留一个活动安装版本。安装时必须与界面读取的当前安装编号一致，避免覆盖另一位管理员刚完成的操作。相同版本号不能替换当前活动版本。

安装新版后，旧程序包和旧执行进程开始释放；历史安装记录、发布定义及工具调用记录继续保留。原配置显示“插件有更新，请确认”，维护者需要明确选择版本、配置并重新发布。卸载后显示“插件已下架，请移除”。重新安装同一个版本会得到新的安装编号，旧引用也不会自动恢复。

智能体开始新任务时排除失效插件。已经进入模型上下文的旧工具若再次被调用，平台仍会在执行入口拒绝，并返回明确的版本变更原因。正在执行的旧插件进程会被终止；如果已经提交外部修改，提示中同时说明结果需要核对。

## 运行与部署条件

后端运行环境需要可执行的 Java 21。开发和测试使用实际类路径；生产从应用可执行包通过 PropertiesLauncher（可选择主方法的启动器）启动插件执行入口，不需要单独启动插件 Web 服务。

程序包最大为 20 MiB（约 21 MB），展开内容最大 80 MiB，单个描述文件最大 64 KiB。每个企业最多保留 100 个活动或待安装包。未确认安装的包一天后清除文件内容。

每台后端最多同时运行四个插件进程，每个进程的 Java 堆内存上限为 256 MiB，类元数据内存上限为 192 MiB。进程间单条消息上限为 4 MiB。执行临时目录由平台创建并在结束后清理。子进程不会继承平台数据库口令和密钥环境变量。

安装状态和程序包保存在数据库中，多服务实例不依赖共享本地目录。版本切换事务提交后立即禁止旧版本新调用；本机结束已登记旧进程，其他实例在等待消息期间最多每 200 毫秒重新检查一次状态。检查耗时还受数据库响应时间影响，不保证跨机器同时停止。

独立 Java 进程仍具有其运行账号的操作系统权限，因此只有平台管理员可以安装受信任的代码。此功能不提供不可信代码沙盒。插件确需访问外部业务时，应由部署人员配置最小必要的文件与网络权限。

数据库新增迁移 `V0.3.0.3__新增Java插件安装记录.sql`。保留历史迁移内容，使用正常应用升级执行；不需要清库或修复已有迁移记录。部署数据库须允许保存最大 20 MiB 的程序包，反向代理也需保留相应上传大小限制。
