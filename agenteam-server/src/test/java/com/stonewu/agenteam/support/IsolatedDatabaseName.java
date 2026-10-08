package com.stonewu.agenteam.support;

/**
 * 只允许本次测试容器中随机生成的隔离数据库名称。
 */
public record IsolatedDatabaseName(String value) {
    public IsolatedDatabaseName {
        if (value == null || !value.matches("agenteam_test_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("拒绝访问不是测试辅助类创建的数据库");
        }
    }
}
