package com.stonewu.agenteam.support;

import org.testcontainers.mysql.MySQLContainer;

/** 使用实际 MySQL 服务验证 MariaDB 驱动，不依赖 Oracle 驱动的测试容器默认值。 */
public final class MysqlCompatibleContainer extends MySQLContainer {
    public MysqlCompatibleContainer() {
        super("mysql:8.4.11");
        withUrlParam("permitMysqlScheme", "true");
        withUrlParam("connectionTimeZone", "UTC");
        withUrlParam("forceConnectionTimeZoneToSession", "true");
        withUrlParam("preserveInstants", "true");
        withUrlParam("allowLocalInfile", "false");
        withUrlParam("sslMode", "trust");
    }

    @Override
    public String getDriverClassName() {
        return "org.mariadb.jdbc.Driver";
    }
}
