package com.stonewu.examples.jsontools;

/**
 * 给用户的错误只含固定说明与位置；原始解析异常只用于脱敏后的排查日志。
 */
final class JsonInputException extends IllegalArgumentException {
    final String code;
    final int line;
    final int column;

    JsonInputException(String code, String message) {
        this(code, message, 0, 0, null);
    }

    JsonInputException(String code, String message, int line, int column, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.line = line;
        this.column = column;
    }
}
