# 代码与依赖的版权信息

AgenTeam 的项目许可证保存在 `LICENSE`，版权告知保存在 `NOTICE`。依赖包和源文件中已有的版权内容按原文保留，不用项目声明替换。

## 查看已有版权文件

后端、前端、执行服务及办公镜像中的项目版权文件位于 `/usr/share/licenses/agenteam/agenteam/`；解出的部署文件使用 `licenses/agenteam/`。可以使用 `docker cp` 从容器取得，不需要启动应用或连接数据库。

Java 依赖作为独立文件保存在可执行包的 `BOOT-INF/lib/` 中。前端源文件、依赖包及系统软件中原有的版权信息继续保留在各自目录。

## 数据库连接驱动

应用通过 MariaDB Connector/J 3.5.10（Java 数据库连接驱动）连接 MySQL。驱动采用 LGPL-2.1-or-later（GNU 宽通用公共许可证第二点一版或更新版本），其原有许可与项目的 Apache-2.0 许可分别保留。

驱动位于 `BOOT-INF/lib/mariadb-java-client-3.5.10.jar`。准备好兼容的驱动文件后，可以在应用包副本中替换，用于调试相关修改：

```sh
jar uf0 应用包副本.jar BOOT-INF/lib/mariadb-java-client-3.5.10.jar
```

其中 `0` 表示以未压缩形式保存嵌套库，便于 Spring Boot（应用启动与打包框架）加载。保留原始应用包，并在独立环境确认替换结果。

## 发布内容

版本更新日志只展示本项目的重要新特性、问题修复和体验优化，不附加第三方声明、源码归档、分卷或下载索引。构建直接保留已有版权文件，不要求为发版另外整理声明资料。
