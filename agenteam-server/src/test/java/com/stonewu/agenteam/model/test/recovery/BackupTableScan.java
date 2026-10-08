package com.stonewu.agenteam.model.test.recovery;

import java.util.List;

/**
 * 库表和主键顺序来自当前隔离库的真实结构，只接受普通字段名称。
 */
public record BackupTableScan(String table, List<String> keys) {
    public BackupTableScan {
        if (table == null || !table.matches("[a-z_]+") || keys == null || keys.isEmpty()
            || keys.stream().anyMatch(value -> value == null || !value.matches("[a-z_]+"))) {
            throw new IllegalArgumentException("恢复检查需要明确的库表和主键顺序");
        }
        keys = List.copyOf(keys);
    }
}
