package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.enterprise.entity.OrganizationViewQueryRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * OrganizationViewMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface OrganizationViewSqlMapper extends MPJBaseMapper<EnterpriseRow> {
    List<String> memberIds(@Param("scope") OwnerQueryScope scope, @Param("query") String query,
                           @Param("teamId") String teamId, @Param("status") String status,
                           @Param("after") PagePosition after, @Param("count") int count);

    List<OrganizationViewQueryRow> listTeams(@Param("scope") OwnerQueryScope scope, @Param("query") String query,
                                             @Param("after") PagePosition after, @Param("count") int count);

    List<OrganizationViewQueryRow> listRoles(@Param("enterprise") String enterprise, @Param("query") String query,
                                             @Param("after") PagePosition after, @Param("count") int count);

    List<OrganizationViewQueryRow> findRole(@Param("enterprise") String enterprise, @Param("id") String id);


    List<OrganizationViewQueryRow> enterprisesEnterprise(@Param("systemAdministrator") boolean systemAdministrator,
                                                         @Param("userId") String userId);

    default List<OrganizationViewQueryRow> enterpriseEnterprise(String id) {
        var criteria = new LambdaQueryWrapper<EnterpriseRow>().eq(EnterpriseRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new OrganizationViewQueryRow();
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
            if (storedRow.getDescription() != null) {
                mappedRow.setDescription(storedRow.getDescription());
            }
            if (storedRow.getContactEmail() != null) {
                mappedRow.setContactEmail(storedRow.getContactEmail());
            }
            if (storedRow.getTimezone() != null) {
                mappedRow.setTimezone(storedRow.getTimezone());
            }
            if (storedRow.getQuotaTimezone() != null) {
                mappedRow.setQuotaTimezone(storedRow.getQuotaTimezone());
            }
            if (storedRow.getPendingQuotaTimezone() != null) {
                mappedRow.setPendingQuotaTimezone(storedRow.getPendingQuotaTimezone());
            }
            if (storedRow.getRetentionDays() != null) {
                mappedRow.setRetentionDays(storedRow.getRetentionDays());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(Objects.toString(storedRow.getRevision(), null));
            }
            return mappedRow;
        }).toList();
    }

    List<OrganizationViewQueryRow> teamEnterpriseTeamMember(@Param("enterpriseId") String enterpriseId,
                                                            @Param("id") String id);
}
