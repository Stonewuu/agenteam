package com.stonewu.agenteam.mapper.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.security.entity.CredentialQueryRow;
import com.stonewu.agenteam.model.security.entity.CredentialRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * CredentialMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface CredentialSqlMapper extends MPJBaseMapper<CredentialRow> {
    default List<CredentialQueryRow> findCredential(String enterprise, String id) {
        var criteria = new LambdaQueryWrapper<CredentialRow>().select(CredentialRow::getId,
            CredentialRow::getEnterpriseId, CredentialRow::getKind, CredentialRow::getStatus,
            CredentialRow::getKeyVersion, CredentialRow::getNonce, CredentialRow::getCiphertext,
            CredentialRow::getAuthTag).eq(CredentialRow::getEnterpriseId, enterprise).eq(CredentialRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new CredentialQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getKind() != null) {
                mappedRow.setKind(storedRow.getKind());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getKeyVersion() != null) {
                mappedRow.setKeyVersion(storedRow.getKeyVersion());
            }
            if (storedRow.getNonce() != null) {
                mappedRow.setNonce(storedRow.getNonce());
            }
            if (storedRow.getCiphertext() != null) {
                mappedRow.setCiphertext(storedRow.getCiphertext());
            }
            if (storedRow.getAuthTag() != null) {
                mappedRow.setAuthTag(storedRow.getAuthTag());
            }
            return mappedRow;
        }).toList();
    }

    default int storeCredential(byte[] encrypted, byte[] nonce, byte[] tag, String keyVersion, String actor,
                                Timestamp now, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<CredentialRow>().eq(CredentialRow::getEnterpriseId, enterprise)
            .eq(CredentialRow::getId, id).set(CredentialRow::getCiphertext, encrypted)
            .set(CredentialRow::getNonce, nonce).set(CredentialRow::getAuthTag, tag)
            .set(CredentialRow::getKeyVersion, keyVersion).set(CredentialRow::getStatus, "active")
            .set(CredentialRow::getUpdatedBy, actor).setIncrBy(CredentialRow::getRevision, 1)
            .set(CredentialRow::getUpdatedAt, now));
    }

    default int insertCredential(String id, String enterprise, String name, String kind, byte[] encrypted, byte[] nonce,
                                 byte[] tag, String keyVersion, String actor, Timestamp now) {
        var databaseRow = new CredentialRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setName(name);
        databaseRow.setKind(kind);
        databaseRow.setCiphertext(encrypted);
        databaseRow.setNonce(nonce);
        databaseRow.setAuthTag(tag);
        databaseRow.setKeyVersion(keyVersion);
        databaseRow.setUpdatedBy(actor);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }
}
