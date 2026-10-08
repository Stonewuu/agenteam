package com.stonewu.agenteam.mapper.enterprise;

import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalRecipientQueryRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MemberRemovalRecipientMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface MemberRemovalRecipientSqlMapper {
    List<MemberRemovalRecipientQueryRow> listRecipients(@Param("enterprise") String enterprise,
                                                        @Param("departing") String departing,
                                                        @Param("required") List<String> required,
                                                        @Param("todo") boolean todo, @Param("query") String query,
                                                        @Param("after") PagePosition after, @Param("limit") int limit);
}
