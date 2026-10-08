package com.stonewu.agenteam.model.test.source;

/**
 * 只允许随机生成的测试账号，库名必须是测试基础设施创建的隔离库。
 */
public record DatabaseTestAccount(String database, String username, String password) {
    public DatabaseTestAccount {
        if (database == null || !database.matches("agenteam_test_[a-f0-9]{32}")
            || username == null || !username.matches("p05(?:r_|w_|_source_)[a-f0-9]{16,20}")) {
            throw new IllegalArgumentException("数据库来源测试只允许访问隔离库与随机测试账号");
        }
    }
}
