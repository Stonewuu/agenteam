package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseQueryRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * EnterpriseMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface EnterpriseSqlMapper extends MPJBaseMapper<EnterpriseRow> {
    default List<EnterpriseQueryRow> findByIdEnterprise(String enterpriseId) {
        var criteria = new LambdaQueryWrapper<EnterpriseRow>().select(EnterpriseRow::getId, EnterpriseRow::getName,
                EnterpriseRow::getStatus, EnterpriseRow::getCreatedAt, EnterpriseRow::getUpdatedAt)
            .eq(EnterpriseRow::getId, enterpriseId);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new EnterpriseQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getName() != null) {
                mappedRow.setName(storedRow.getName());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            if (storedRow.getUpdatedAt() != null) {
                mappedRow.setUpdatedAt(
                    (storedRow.getUpdatedAt() == null ? null : Timestamp.from(storedRow.getUpdatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<String> lockEnterpriseEnterprise(@Param("enterpriseId") String enterpriseId);

    default List<String> timezoneEnterprise(String enterpriseId) {
        var criteria = new LambdaQueryWrapper<EnterpriseRow>().select(EnterpriseRow::getTimezone)
            .eq(EnterpriseRow::getId, enterpriseId);
        return selectList(criteria).stream().map(storedRow -> storedRow.getTimezone()).toList();
    }

    default int insertEnterprise(String enterpriseId, String name, String description, String email, String timezone,
                                 Timestamp quotaPeriodStart, Timestamp time2, String createdBy, Timestamp now) {
        var databaseRow = new EnterpriseRow();
        databaseRow.setId(enterpriseId);
        databaseRow.setName(name);
        databaseRow.setDescription(description);
        databaseRow.setContactEmail(email);
        databaseRow.setTimezone(timezone);
        databaseRow.setQuotaTimezone(timezone);
        databaseRow.setQuotaPeriodStart((quotaPeriodStart == null ? null : quotaPeriodStart.toInstant()));
        databaseRow.setQuotaPeriodEnd((time2 == null ? null : time2.toInstant()));
        databaseRow.setCreatedBy(createdBy);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }


    default int advancePermissionVersionEnterprise(String enterpriseId) {
        return update(new LambdaUpdateWrapper<EnterpriseRow>().eq(EnterpriseRow::getId, enterpriseId)
            .setIncrBy(EnterpriseRow::getPermissionVersion, 1));
    }


}
