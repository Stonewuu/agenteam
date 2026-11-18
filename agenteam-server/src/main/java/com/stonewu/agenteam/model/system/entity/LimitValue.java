package com.stonewu.agenteam.model.system.entity;

import java.time.Duration;
import java.time.Instant;

/** 业务限制统一使用 -1 表示不限；框架的特殊值只在调用边界转换。 */
public final class LimitValue {
    public static final int UNLIMITED = -1;

    private LimitValue() {
    }

    public static boolean unlimited(long value) {
        return value == UNLIMITED;
    }

    public static boolean unlimited(Long value) {
        return value == null || value == UNLIMITED;
    }

    public static boolean validInteger(long value, long minimum) {
        return unlimited(value) || value >= minimum && value <= Integer.MAX_VALUE;
    }

    public static boolean reached(long used, long maximum) {
        return !unlimited(maximum) && used >= maximum;
    }

    public static boolean exceeded(long used, long maximum) {
        return !unlimited(maximum) && used > maximum;
    }

    public static boolean ordered(long smaller, long larger) {
        return unlimited(smaller) || unlimited(larger) || smaller <= larger;
    }

    public static Duration seconds(long value) {
        return unlimited(value) ? Duration.ZERO : Duration.ofSeconds(value);
    }

    /** 无截止时间保存为空；较长的期限最多保存到数据库日期类型的末尾。 */
    public static Instant deadline(Instant now, long seconds) {
        if (unlimited(seconds)) {
            return null;
        }
        Instant maximum = Instant.parse("9999-12-31T23:59:59.999Z");
        return now.plusSeconds(Math.min(seconds, Duration.between(now, maximum).getSeconds()));
    }

    public static boolean expired(Instant deadline, Instant now) {
        return deadline != null && !deadline.isAfter(now);
    }

    /** 单个字符串及字节数组使用 int 长度，换算时不能溢出为负数。 */
    public static int textBytes(long amount, int unit) {
        return unlimited(amount) ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, amount * unit);
    }

    public static long bytes(long amount, long unit) {
        return unlimited(amount) ? Long.MAX_VALUE : Math.multiplyExact(amount, unit);
    }

    public static long addCapacity(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /** 只兼容原来明确使用零值表示不限的智能体字段。 */
    public static int legacyZero(int value) {
        return value == 0 ? UNLIMITED : value;
    }

    public static Long legacyNull(Long value) {
        return value == null ? (long) UNLIMITED : value;
    }
}
