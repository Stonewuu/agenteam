# 程序包的许可与依赖

JSON 数据助手示例采用 Apache License 2.0（Apache 第二版开源许可证），完整条款见 `LICENSE`，并随程序包保存在 `META-INF/LICENSE`。

程序包合并 Jackson Databind 2.21.5、Jackson Core 2.21.5 和 Jackson Annotations 2.21（JSON 数据读取、生成与类型标注库），采用 Apache License 2.0。原有声明保存在 `META-INF/NOTICE`。Jackson Core 自带的数字解析实现声明保留在 `META-INF/FastDoubleParser-LICENSE`、`META-INF/FastDoubleParser-ThirdParty-LICENSE` 和 `META-INF/Schubfach-LICENSE` 中。

上述解析库内部包名改为 `com.stonewu.examples.jsontools.internal.jackson`，以防平台或其他插件使用不同版本造成冲突。平台提供的 `agenteam-tool-api`、`agenteam-plugin-api` 与 PF4J（Java 动态插件框架）不包含在上传程序包中。
