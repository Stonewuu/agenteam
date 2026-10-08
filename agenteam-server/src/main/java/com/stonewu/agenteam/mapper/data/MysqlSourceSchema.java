package com.stonewu.agenteam.mapper.data;

import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.entity.DataQueryBudget;
import com.stonewu.agenteam.service.data.mysql.MysqlReadOnlyConnections;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.*;

/**
 * 查询之前从实际数据库读取表和列，不能把前端声明当成已经存在的结构。
 */
@Component
public class MysqlSourceSchema {
    public record Column(String name, String type, String nativeType, boolean nullable, Integer precision,
                         Integer scale) {
    }

    public Map<String, Column> require(MysqlReadOnlyConnections.Session session, String table,
                                       List<DataField> fields) throws SQLException {
        if (table == null || table.isBlank() || table.codePointCount(0, table.length()) > 64 || table.codePoints()
            .anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("sourceName", "请填写真实的表名或视图名。");
        }
        var budget = new DataQueryBudget(session.deadline());
        if (session.mapper().findTables(session.database(), table, budget).stream().noneMatch(table::equals)) {
            throw unavailable();
        }
        Map<String, Column> columns = new LinkedHashMap<>();
        for (var row : session.mapper().readColumns(session.database(), table, budget)) {
            String name = row.name(), nativeType = row.nativeType()
                .toLowerCase(Locale.ROOT), declaration = row.declaration().toLowerCase(Locale.ROOT);
            String type = switch (nativeType) {
                case "tinyint" -> declaration.startsWith("tinyint(1)") ? "boolean" : "integer";
                case "smallint", "mediumint", "int", "integer", "bigint", "year" -> "integer";
                case "decimal", "numeric", "float", "double", "real" -> "decimal";
                case "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set", "time" -> "string";
                case "date" -> "date";
                case "datetime", "timestamp" -> "datetime";
                case "json" -> "object";
                case "bit" -> declaration.equals("bit(1)") ? "boolean" : "unsupported";
                default -> "unsupported";
            };
            columns.put(name,
                new Column(name, type, nativeType, row.nullable().equals("YES"), row.precision(), row.scale()));
        }
        for (var field : fields) {
            var column = columns.get(field.name());
            if (column == null || column.type().equals("unsupported") || !compatible(column, field)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_SOURCE_FIELD_MISMATCH",
                    "所选字段不存在或与数据库类型不匹配，请重新检查集合字段。", Map.of("field", field.name()), Map.of());
            }
            if (!field.nullable() && column.nullable()) {
                throw ApiException.invalidField("fields", "数据库允许空值的字段不能声明为非空。");
            }
        }
        return Map.copyOf(columns);
    }

    private boolean compatible(Column column, DataField field) {
        if (field.valueType().equals("string")) {
            return true;
        }
        if (field.valueType().equals("integer") && column.type().equals("boolean")) {
            return true;
        }
        if (field.valueType().equals("decimal") && column.type().equals("integer")) {
            return true;
        }
        if (field.valueType().equals("integer") && column.type().equals("decimal") && Integer.valueOf(0)
            .equals(column.scale())) {
            return true;
        }
        if (!column.type().equals(field.valueType())) {
            return false;
        }
        return !field.valueType().equals("decimal") || !Set.of("decimal", "numeric").contains(column.nativeType())
            || (column.scale() != null && column.precision() != null && column.scale() <= 30 && column.precision() - column.scale() <= 35);
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "DATA_SOURCE_TABLE_UNAVAILABLE",
            "所选库表或视图不存在，或当前账号没有读取资格。");
    }
}
