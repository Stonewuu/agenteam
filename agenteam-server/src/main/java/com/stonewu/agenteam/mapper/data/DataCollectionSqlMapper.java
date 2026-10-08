package com.stonewu.agenteam.mapper.data;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.data.entity.DataCollectionQueryRow;
import com.stonewu.agenteam.model.data.entity.DataCollectionRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * DataCollectionMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface DataCollectionSqlMapper extends MPJBaseMapper<DataCollectionRow> {
    List<DataCollectionQueryRow> availableCollections(@Param("enterprise") String enterprise,
                                                      @Param("resource") String resource,
                                                      @Param("sourceHash") String sourceHash,
                                                      @Param("position") PagePosition position,
                                                      @Param("limit") int limit);

    default List<DataCollectionQueryRow> listCollections(String enterprise, String resource, PagePosition position,
                                                         int limit) {
        var criteria = new LambdaQueryWrapper<DataCollectionRow>().orderByDesc(DataCollectionRow::getUpdatedAt)
            .orderByDesc(DataCollectionRow::getId).eq(DataCollectionRow::getEnterpriseId, enterprise)
            .eq(DataCollectionRow::getResourceId, resource);
        if (position != null) {
            criteria.and(group -> group.lt(DataCollectionRow::getUpdatedAt, position.time())
                .or(other -> other.eq(DataCollectionRow::getUpdatedAt, position.time())
                    .lt(DataCollectionRow::getId, position.id())));
        }
        long pageSize = limit;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<DataCollectionRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> {
                var mappedRow = new DataCollectionQueryRow();
                if (storedRow.getId() != null) {
                    mappedRow.setId(storedRow.getId());
                }
                if (storedRow.getEnterpriseId() != null) {
                    mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
                }
                if (storedRow.getResourceId() != null) {
                    mappedRow.setResourceId(storedRow.getResourceId());
                }
                if (storedRow.getName() != null) {
                    mappedRow.setName(storedRow.getName());
                }
                if (storedRow.getSourceName() != null) {
                    mappedRow.setSourceName(storedRow.getSourceName());
                }
                if (storedRow.getActiveGeneration() != null) {
                    mappedRow.setActiveGeneration(storedRow.getActiveGeneration());
                }
                if (storedRow.getFileId() != null) {
                    mappedRow.setFileId(storedRow.getFileId());
                }
                if (storedRow.getRowCount() != null) {
                    mappedRow.setRowCount(storedRow.getRowCount());
                }
                if (storedRow.getStatus() != null) {
                    mappedRow.setStatus(storedRow.getStatus());
                }
                if (storedRow.getRevision() != null) {
                    mappedRow.setRevision(storedRow.getRevision());
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


    default List<DataCollectionQueryRow> findDataCollection(String enterprise, String id, boolean lock) {
        if (lock) {
            return findDataCollectionLocked(enterprise, id, lock);
        }
        var criteria = new LambdaQueryWrapper<DataCollectionRow>().eq(DataCollectionRow::getEnterpriseId, enterprise)
            .eq(DataCollectionRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new DataCollectionQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getResourceId() != null) {
                mappedRow.setResourceId(storedRow.getResourceId());
            }
            if (storedRow.getName() != null) {
                mappedRow.setName(storedRow.getName());
            }
            if (storedRow.getSourceName() != null) {
                mappedRow.setSourceName(storedRow.getSourceName());
            }
            if (storedRow.getActiveGeneration() != null) {
                mappedRow.setActiveGeneration(storedRow.getActiveGeneration());
            }
            if (storedRow.getFileId() != null) {
                mappedRow.setFileId(storedRow.getFileId());
            }
            if (storedRow.getRowCount() != null) {
                mappedRow.setRowCount(storedRow.getRowCount());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
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

    List<DataCollectionQueryRow> findDataCollectionLocked(@Param("enterprise") String enterprise,
                                                          @Param("id") String id, @Param("lock") boolean lock);

    default List<DataCollectionQueryRow> allDataCollection(String enterprise, String resource) {
        var criteria = new LambdaQueryWrapper<DataCollectionRow>().orderByAsc(DataCollectionRow::getId)
            .eq(DataCollectionRow::getEnterpriseId, enterprise).eq(DataCollectionRow::getResourceId, resource);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new DataCollectionQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getResourceId() != null) {
                mappedRow.setResourceId(storedRow.getResourceId());
            }
            if (storedRow.getName() != null) {
                mappedRow.setName(storedRow.getName());
            }
            if (storedRow.getSourceName() != null) {
                mappedRow.setSourceName(storedRow.getSourceName());
            }
            if (storedRow.getActiveGeneration() != null) {
                mappedRow.setActiveGeneration(storedRow.getActiveGeneration());
            }
            if (storedRow.getFileId() != null) {
                mappedRow.setFileId(storedRow.getFileId());
            }
            if (storedRow.getRowCount() != null) {
                mappedRow.setRowCount(storedRow.getRowCount());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
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


    default int createDataCollection(String id, String enterprise, String resource, String name, String source,
                                     String file, Long rows, Timestamp now) {
        var databaseRow = new DataCollectionRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setResourceId(resource);
        databaseRow.setName(name);
        databaseRow.setSourceName(source);
        databaseRow.setActiveGeneration(1);
        databaseRow.setFileId(file);
        databaseRow.setRowCount(rows);
        databaseRow.setStatus("active");
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }


    default int activateDataCollection(String name, String source, int generation, String file, Long rows,
                                       Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<DataCollectionRow>().eq(DataCollectionRow::getEnterpriseId, enterpriseId)
            .eq(DataCollectionRow::getId, id).eq(DataCollectionRow::getRevision, revision)
            .set(DataCollectionRow::getName, name).set(DataCollectionRow::getSourceName, source)
            .set(DataCollectionRow::getActiveGeneration, generation).set(DataCollectionRow::getFileId, file)
            .set(DataCollectionRow::getRowCount, rows).set(DataCollectionRow::getStatus, "active")
            .setIncrBy(DataCollectionRow::getRevision, 1).set(DataCollectionRow::getUpdatedAt, now));
    }
}
