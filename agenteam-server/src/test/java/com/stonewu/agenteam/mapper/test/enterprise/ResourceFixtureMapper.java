package com.stonewu.agenteam.mapper.test.enterprise;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;

/** 资源记录的测试数据准备。 */
@Mapper
public interface ResourceFixtureMapper extends MPJBaseMapper<ResourceRow> {
    default int insertAgent(String id, String enterprise, String name, String owner) {
        return insert(agent(id, enterprise, name, owner));
    }

    default int insertSearchableAgent(String id, String enterprise, String name, String owner, Instant updatedAt) {
        var row = agent(id, enterprise, name, owner);
        row.setDescription("真实搜索摘要");
        row.setSubtype("chat");
        row.setSource("created");
        row.setStatus("active");
        row.setUpdatedAt(updatedAt);
        return insert(row);
    }

    default int insertDisabledAgent(String id, String enterprise, String owner) {
        var row = agent(id, enterprise, "已停用的历史员工", owner);
        row.setDescription("");
        row.setSubtype("chat");
        row.setSource("created");
        row.setStatus("disabled");
        return insert(row);
    }

    private static ResourceRow agent(String id, String enterprise, String name, String owner) {
        var row = new ResourceRow();
        row.setId(id);
        row.setEnterpriseId(enterprise);
        row.setKind("agent");
        row.setName(name);
        row.setOwnerUserId(owner);
        return row;
    }
}
