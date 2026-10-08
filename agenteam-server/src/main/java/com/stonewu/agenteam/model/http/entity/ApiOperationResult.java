package com.stonewu.agenteam.model.http.entity;

/**
 * 内部执行结果；是否复用结果不作为用户界面状态。
 */
public record ApiOperationResult(int status, Object data, boolean replayed) {
    public static ApiOperationResult of(int status, Object data) {
        return new ApiOperationResult(status, data, false);
    }
}
