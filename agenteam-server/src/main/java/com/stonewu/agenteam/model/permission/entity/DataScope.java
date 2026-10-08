package com.stonewu.agenteam.model.permission.entity;

/**
 * 单项操作允许接触的数据范围；团队范围包含本人。
 */
public enum DataScope {
    OWN("own", 0), TEAM("team", 1), ENTERPRISE("enterprise", 2);

    private final String code;
    private final int rank;

    DataScope(String code, int rank) {
        this.code = code;
        this.rank = rank;
    }

    public String code() {
        return code;
    }

    public boolean covers(DataScope requested) {
        return rank >= requested.rank;
    }

    public static DataScope fromCode(String code) {
        for (DataScope scope : values()) {
            if (scope.code.equals(code)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("数据范围未定义");
    }

    public static DataScope fromRank(int rank) {
        return values()[rank];
    }
}
