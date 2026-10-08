package com.stonewu.agenteam.mapper.file;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.file.entity.FileQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * FileMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface FileSqlMapper extends MPJBaseMapper<FileObjectRow> {
    default List<FileQueryRow> findFileObject(String enterprise, String id, boolean lock) {
        if (lock) {
            return findFileObjectLocked(enterprise, id, lock);
        }
        var criteria = new LambdaQueryWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterprise)
            .eq(FileObjectRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new FileQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getOwnerUserId() != null) {
                mappedRow.setOwnerUserId(storedRow.getOwnerUserId());
            }
            if (storedRow.getResourceId() != null) {
                mappedRow.setResourceId(storedRow.getResourceId());
            }
            if (storedRow.getResourceVersionId() != null) {
                mappedRow.setResourceVersionId(storedRow.getResourceVersionId());
            }
            if (storedRow.getRunId() != null) {
                mappedRow.setRunId(storedRow.getRunId());
            }
            if (storedRow.getPurpose() != null) {
                mappedRow.setPurpose(storedRow.getPurpose());
            }
            if (storedRow.getOriginalName() != null) {
                mappedRow.setOriginalName(storedRow.getOriginalName());
            }
            if (storedRow.getMediaType() != null) {
                mappedRow.setMediaType(storedRow.getMediaType());
            }
            if (storedRow.getExpectedSizeBytes() != null) {
                mappedRow.setExpectedSizeBytes(storedRow.getExpectedSizeBytes());
            }
            if (storedRow.getExpectedSha256() != null) {
                mappedRow.setExpectedSha256(storedRow.getExpectedSha256());
            }
            if (storedRow.getUploadExpiresAt() != null) {
                mappedRow.setUploadExpiresAt(
                    (storedRow.getUploadExpiresAt() == null ? null : Timestamp.from(storedRow.getUploadExpiresAt())));
            }
            if (storedRow.getUploadLeaseId() != null) {
                mappedRow.setUploadLeaseId(storedRow.getUploadLeaseId());
            }
            if (storedRow.getUploadLeaseUntil() != null) {
                mappedRow.setUploadLeaseUntil(
                    (storedRow.getUploadLeaseUntil() == null ? null : Timestamp.from(storedRow.getUploadLeaseUntil())));
            }
            if (storedRow.getSizeBytes() != null) {
                mappedRow.setSizeBytes(storedRow.getSizeBytes());
            }
            if (storedRow.getSha256() != null) {
                mappedRow.setSha256(storedRow.getSha256());
            }
            if (storedRow.getStorageKey() != null) {
                mappedRow.setStorageKey(storedRow.getStorageKey());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getErrorCode() != null) {
                mappedRow.setErrorCode(storedRow.getErrorCode());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getDeletedAt() != null) {
                mappedRow.setDeletedAt(
                    (storedRow.getDeletedAt() == null ? null : Timestamp.from(storedRow.getDeletedAt())));
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<FileQueryRow> findFileObjectLocked(@Param("enterprise") String enterprise, @Param("id") String id,
                                            @Param("lock") boolean lock);

    default int prepareFileObject(String id, String enterprise, String owner, String resourceId, String purpose,
                                  String name, String mediaType, long sizeBytes, String sha256,
                                  Timestamp uploadExpiresAt, String value, String storageKey, Timestamp time2,
                                  Timestamp now) {
        var databaseRow = new FileObjectRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setOwnerUserId(owner);
        databaseRow.setResourceId(resourceId);
        databaseRow.setPurpose(purpose);
        databaseRow.setOriginalName(name);
        databaseRow.setMediaType(mediaType);
        databaseRow.setExpectedSizeBytes(sizeBytes);
        databaseRow.setExpectedSha256(sha256);
        databaseRow.setUploadExpiresAt((uploadExpiresAt == null ? null : uploadExpiresAt.toInstant()));
        databaseRow.setSizeBytes(0L);
        databaseRow.setSha256(value);
        databaseRow.setStorageKey(storageKey);
        databaseRow.setExpiresAt((time2 == null ? null : time2.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int generatedFileObject(String id, String enterpriseId, String ownerUserId, String resourceId,
                                    String resourceVersionId, String runId, String purpose, String name,
                                    String mediaType, long size, String sha256, String storageKey, Timestamp expiresAt,
                                    Timestamp now) {
        var databaseRow = new FileObjectRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setOwnerUserId(ownerUserId);
        databaseRow.setResourceId(resourceId);
        databaseRow.setResourceVersionId(resourceVersionId);
        databaseRow.setRunId(runId);
        databaseRow.setPurpose(purpose);
        databaseRow.setOriginalName(name);
        databaseRow.setMediaType(mediaType);
        databaseRow.setSizeBytes(size);
        databaseRow.setSha256(sha256);
        databaseRow.setStorageKey(storageKey);
        databaseRow.setStatus("ready");
        databaseRow.setExpiresAt((expiresAt == null ? null : expiresAt.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int claimUploadFileObject(String lease, Timestamp uploadLeaseUntil, Timestamp now, String enterpriseId,
                                      String id) {
        return update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterpriseId)
            .eq(FileObjectRow::getId, id).eq(FileObjectRow::getStatus, "pending")
            .gt(FileObjectRow::getUploadExpiresAt, now).and(group -> group.isNull(FileObjectRow::getUploadLeaseUntil)
                .or(other -> other.le(FileObjectRow::getUploadLeaseUntil, now)))
            .set(FileObjectRow::getUploadLeaseId, lease).set(FileObjectRow::getUploadLeaseUntil, uploadLeaseUntil)
            .set(FileObjectRow::getUpdatedAt, now));
    }

    default int uploadedFileObject(String key, long size, String sha256, Timestamp now, String enterpriseId, String id,
                                   String lease) {
        return update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterpriseId)
            .eq(FileObjectRow::getId, id).eq(FileObjectRow::getStatus, "pending")
            .eq(FileObjectRow::getUploadLeaseId, lease).gt(FileObjectRow::getUploadLeaseUntil, now)
            .gt(FileObjectRow::getUploadExpiresAt, now).set(FileObjectRow::getStatus, "uploaded")
            .set(FileObjectRow::getStorageKey, key).set(FileObjectRow::getSizeBytes, size)
            .set(FileObjectRow::getSha256, sha256).set(FileObjectRow::getUploadLeaseId, null)
            .set(FileObjectRow::getUploadLeaseUntil, null).set(FileObjectRow::getUpdatedAt, now));
    }

    default int releaseUploadFileObject(String enterprise, String id, String lease) {
        return update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterprise)
            .eq(FileObjectRow::getId, id).eq(FileObjectRow::getUploadLeaseId, lease)
            .set(FileObjectRow::getUploadLeaseId, null).set(FileObjectRow::getUploadLeaseUntil, null));
    }

    default int scanningFileObject(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterpriseId)
            .eq(FileObjectRow::getId, id).eq(FileObjectRow::getStatus, "uploaded")
            .set(FileObjectRow::getStatus, "scanning").set(FileObjectRow::getErrorCode, null)
            .set(FileObjectRow::getUpdatedAt, now));
    }

    default int inspectedFileObject(String status, String errorCode, Timestamp now, String enterpriseId, String id,
                                    String sha256) {
        return update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterpriseId)
            .eq(FileObjectRow::getId, id).eq(FileObjectRow::getStatus, "scanning").eq(FileObjectRow::getSha256, sha256)
            .set(FileObjectRow::getStatus, status).set(FileObjectRow::getErrorCode, errorCode)
            .set(FileObjectRow::getUpdatedAt, now));
    }

    default int deletedFileObject(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterpriseId)
            .eq(FileObjectRow::getId, id).isNull(FileObjectRow::getDeletedAt).set(FileObjectRow::getStatus, "deleted")
            .set(FileObjectRow::getDeletedAt, now).set(FileObjectRow::getUpdatedAt, now)
            .set(FileObjectRow::getExpiresAt, now).set(FileObjectRow::getUploadLeaseId, null)
            .set(FileObjectRow::getUploadLeaseUntil, null));
    }

    default int retainFileObject(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterpriseId)
            .eq(FileObjectRow::getId, id).eq(FileObjectRow::getStatus, "ready").isNull(FileObjectRow::getDeletedAt)
            .set(FileObjectRow::getExpiresAt, null).set(FileObjectRow::getUpdatedAt, now));
    }

    default List<FileQueryRow> expiredFileObject(Timestamp now, int limit) {
        var criteria = new LambdaQueryWrapper<FileObjectRow>().orderByAsc(FileObjectRow::getExpiresAt)
            .orderByAsc(FileObjectRow::getId).le(FileObjectRow::getExpiresAt, now).and(
                group -> group.isNull(FileObjectRow::getUploadLeaseUntil)
                    .or(other -> other.le(FileObjectRow::getUploadLeaseUntil, now)));
        long pageSize = limit;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<FileObjectRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> {
                var mappedRow = new FileQueryRow();
                if (storedRow.getId() != null) {
                    mappedRow.setId(storedRow.getId());
                }
                if (storedRow.getEnterpriseId() != null) {
                    mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
                }
                if (storedRow.getOwnerUserId() != null) {
                    mappedRow.setOwnerUserId(storedRow.getOwnerUserId());
                }
                if (storedRow.getResourceId() != null) {
                    mappedRow.setResourceId(storedRow.getResourceId());
                }
                if (storedRow.getResourceVersionId() != null) {
                    mappedRow.setResourceVersionId(storedRow.getResourceVersionId());
                }
                if (storedRow.getRunId() != null) {
                    mappedRow.setRunId(storedRow.getRunId());
                }
                if (storedRow.getPurpose() != null) {
                    mappedRow.setPurpose(storedRow.getPurpose());
                }
                if (storedRow.getOriginalName() != null) {
                    mappedRow.setOriginalName(storedRow.getOriginalName());
                }
                if (storedRow.getMediaType() != null) {
                    mappedRow.setMediaType(storedRow.getMediaType());
                }
                if (storedRow.getExpectedSizeBytes() != null) {
                    mappedRow.setExpectedSizeBytes(storedRow.getExpectedSizeBytes());
                }
                if (storedRow.getExpectedSha256() != null) {
                    mappedRow.setExpectedSha256(storedRow.getExpectedSha256());
                }
                if (storedRow.getUploadExpiresAt() != null) {
                    mappedRow.setUploadExpiresAt((storedRow.getUploadExpiresAt() == null ? null : Timestamp.from(
                        storedRow.getUploadExpiresAt())));
                }
                if (storedRow.getUploadLeaseId() != null) {
                    mappedRow.setUploadLeaseId(storedRow.getUploadLeaseId());
                }
                if (storedRow.getUploadLeaseUntil() != null) {
                    mappedRow.setUploadLeaseUntil((storedRow.getUploadLeaseUntil() == null ? null : Timestamp.from(
                        storedRow.getUploadLeaseUntil())));
                }
                if (storedRow.getSizeBytes() != null) {
                    mappedRow.setSizeBytes(storedRow.getSizeBytes());
                }
                if (storedRow.getSha256() != null) {
                    mappedRow.setSha256(storedRow.getSha256());
                }
                if (storedRow.getStorageKey() != null) {
                    mappedRow.setStorageKey(storedRow.getStorageKey());
                }
                if (storedRow.getStatus() != null) {
                    mappedRow.setStatus(storedRow.getStatus());
                }
                if (storedRow.getErrorCode() != null) {
                    mappedRow.setErrorCode(storedRow.getErrorCode());
                }
                if (storedRow.getExpiresAt() != null) {
                    mappedRow.setExpiresAt(
                        (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
                }
                if (storedRow.getDeletedAt() != null) {
                    mappedRow.setDeletedAt(
                        (storedRow.getDeletedAt() == null ? null : Timestamp.from(storedRow.getDeletedAt())));
                }
                if (storedRow.getCreatedAt() != null) {
                    mappedRow.setCreatedAt(
                        (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
                }
                return mappedRow;
            }).toList();
    }

    int removeDeletedFileText(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    int removeDeletedFileDataProfile(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    int removeDeletedFileDataRow(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    default int removeDeletedFileObject(String enterpriseId, String id) {
        return delete(new LambdaQueryWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterpriseId)
            .eq(FileObjectRow::getId, id).eq(FileObjectRow::getStatus, "deleted"));
    }

    default List<FileQueryRow> forRunFileObject(String enterprise, String run) {
        var criteria = new LambdaQueryWrapper<FileObjectRow>().orderByAsc(FileObjectRow::getId)
            .eq(FileObjectRow::getEnterpriseId, enterprise).eq(FileObjectRow::getRunId, run);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new FileQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getOwnerUserId() != null) {
                mappedRow.setOwnerUserId(storedRow.getOwnerUserId());
            }
            if (storedRow.getResourceId() != null) {
                mappedRow.setResourceId(storedRow.getResourceId());
            }
            if (storedRow.getResourceVersionId() != null) {
                mappedRow.setResourceVersionId(storedRow.getResourceVersionId());
            }
            if (storedRow.getRunId() != null) {
                mappedRow.setRunId(storedRow.getRunId());
            }
            if (storedRow.getPurpose() != null) {
                mappedRow.setPurpose(storedRow.getPurpose());
            }
            if (storedRow.getOriginalName() != null) {
                mappedRow.setOriginalName(storedRow.getOriginalName());
            }
            if (storedRow.getMediaType() != null) {
                mappedRow.setMediaType(storedRow.getMediaType());
            }
            if (storedRow.getExpectedSizeBytes() != null) {
                mappedRow.setExpectedSizeBytes(storedRow.getExpectedSizeBytes());
            }
            if (storedRow.getExpectedSha256() != null) {
                mappedRow.setExpectedSha256(storedRow.getExpectedSha256());
            }
            if (storedRow.getUploadExpiresAt() != null) {
                mappedRow.setUploadExpiresAt(
                    (storedRow.getUploadExpiresAt() == null ? null : Timestamp.from(storedRow.getUploadExpiresAt())));
            }
            if (storedRow.getUploadLeaseId() != null) {
                mappedRow.setUploadLeaseId(storedRow.getUploadLeaseId());
            }
            if (storedRow.getUploadLeaseUntil() != null) {
                mappedRow.setUploadLeaseUntil(
                    (storedRow.getUploadLeaseUntil() == null ? null : Timestamp.from(storedRow.getUploadLeaseUntil())));
            }
            if (storedRow.getSizeBytes() != null) {
                mappedRow.setSizeBytes(storedRow.getSizeBytes());
            }
            if (storedRow.getSha256() != null) {
                mappedRow.setSha256(storedRow.getSha256());
            }
            if (storedRow.getStorageKey() != null) {
                mappedRow.setStorageKey(storedRow.getStorageKey());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getErrorCode() != null) {
                mappedRow.setErrorCode(storedRow.getErrorCode());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getDeletedAt() != null) {
                mappedRow.setDeletedAt(
                    (storedRow.getDeletedAt() == null ? null : Timestamp.from(storedRow.getDeletedAt())));
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    default List<Integer> referencesKeyFileObject(String key) {
        return List.of(Math.toIntExact(
            selectCount(new LambdaQueryWrapper<FileObjectRow>().eq(FileObjectRow::getStorageKey, key))));
    }
}
