package com.stonewu.agenteam.mapper.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 每批先锁定最多五百个编号，再一次删除已经锁定的记录。
 */
@Mapper
public interface IdentityRetentionSqlMapper {

    int expireInvitations(@Param("enterprise") String enterprise, @Param("now") Instant now);

    List<String> expiredTokenIds(@Param("boundary") Instant boundary);

    List<String> expiredRequestIds(@Param("now") Instant now);

    List<String> expiredMailIds(@Param("boundary") Instant boundary, @Param("now") Instant now);
}
