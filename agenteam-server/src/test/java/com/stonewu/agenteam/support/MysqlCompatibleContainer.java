package com.stonewu.agenteam.support;

import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/** 使用实际 MySQL 服务验证 MariaDB 驱动，不依赖 Oracle 驱动的测试容器默认值。 */
public final class MysqlCompatibleContainer extends MySQLContainer {
    private final boolean memoryStorage;

    public MysqlCompatibleContainer() {
        super("mysql:8.4.11");
        memoryStorage = Boolean.parseBoolean(System.getProperty("agenteam.test.mysql.tmpfs", "true"));
        // 日常测试使用内存文件系统；容量和恢复计时可明确关闭，保留真实磁盘读写。
        if (memoryStorage) {
            withTmpFs(Map.of("/var/lib/mysql", "rw,size=1g"));
        }
        withUrlParam("permitMysqlScheme", "true");
        withUrlParam("connectionTimeZone", "UTC");
        withUrlParam("forceConnectionTimeZoneToSession", "true");
        withUrlParam("preserveInstants", "true");
        withUrlParam("allowLocalInfile", "false");
        withUrlParam("sslMode", "trust");
    }

    @Override
    protected void configure() {
        super.configure();
        if (memoryStorage) {
            // 内存数据目录不需要占用宿主机的原生异步读写名额，避免并发测试库启动失败。
            var command = new ArrayList<String>();
            if (getCommandParts() != null) {
                Collections.addAll(command, getCommandParts());
            }
            command.add("--innodb-use-native-aio=OFF");
            // 小规模测试库控制缓存和日志占用；磁盘容量演练保持默认配置。
            command.add("--innodb-buffer-pool-size=64M");
            command.add("--innodb-redo-log-capacity=16M");
            command.add("--innodb-log-buffer-size=4M");
            setCommand(command.toArray(String[]::new));
        }
    }

    @Override
    public String getDriverClassName() {
        return "org.mariadb.jdbc.Driver";
    }
}
