package com.stonewu.agenteam.support;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 测试用例传入的值转换为数据库实体字段，空值始终保留。
 */
public final class TestDatabaseValues {
    private TestDatabaseValues() {
    }

    public static String string(Object value) {
        return value == null ? null : value.toString();
    }

    public static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
    }

    public static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
    }

    public static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    public static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant time) {
            return time;
        }
        if (value instanceof Timestamp time) {
            return time.toInstant();
        }
        throw new IllegalArgumentException("测试时间必须使用 Instant 或 Timestamp");
    }

    public static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return LocalDate.parse(value.toString());
    }

    public static Boolean bool(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Boolean flag ? flag : integer(value) != 0;
    }

    public static byte[] bytes(Object value) {
        return value == null ? null : (byte[]) value;
    }
}
