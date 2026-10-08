package com.stonewu.agenteam.mapper.test.source;

import com.stonewu.agenteam.model.test.source.DatabaseTestAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账户与授权名称经过测试专用类型校验，密码始终绑定。
 */
@Mapper
public interface DatabaseAccountFixtureMapper {
    void create(@Param("account") DatabaseTestAccount account);

    void drop(@Param("account") DatabaseTestAccount account);

    void allowDatabaseRead(@Param("account") DatabaseTestAccount account);

    void allowDatabaseWrite(@Param("account") DatabaseTestAccount account);

    void allowApiTableRead(@Param("account") DatabaseTestAccount account);
}
