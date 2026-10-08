package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织删除只返回经当前企业授权的关联数量，存在依赖时不自动扩大或丢弃授权。
 */
@Service
public class OrganizationDependencyService {
    private final List<OrganizationDependencies> contributors;

    public OrganizationDependencyService(List<OrganizationDependencies> contributors) {
        this.contributors = contributors;
    }

    public void requireRoleUnused(String enterpriseId, String id) {
        requireUnused(contributors.stream().map(source -> source.role(enterpriseId, id)).toList());
    }

    public void requireTeamUnused(String enterpriseId, String id) {
        requireUnused(contributors.stream().map(source -> source.team(enterpriseId, id)).toList());
    }

    private void requireUnused(List<Map<String, Long>> contributions) {
        Map<String, Long> totals = new LinkedHashMap<>();
        contributions.forEach(values -> values.forEach((key, value) -> {
            if (value > 0) {
                totals.merge(key, value, Long::sum);
            }
        }));
        if (!totals.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "DEPENDENCIES_EXIST",
                "仍有成员或业务记录使用此项，请先处理关联内容。", Map.of("counts", totals), Map.of());
        }
    }
}
