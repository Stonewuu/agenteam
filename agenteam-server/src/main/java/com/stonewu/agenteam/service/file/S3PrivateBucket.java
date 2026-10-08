package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Type;

import java.io.IOException;
import java.util.Set;

/**
 * 确认桶没有向任意用户开放；无法检查权限时不把文件写入未知范围。
 */
public final class S3PrivateBucket {
    private final S3Client client;
    private final String bucket;
    private final ObjectMapper json;

    public S3PrivateBucket(S3Client client, String bucket, ObjectMapper json) {
        this.client = client;
        this.bucket = bucket;
        this.json = json;
    }

    public void requirePrivate() {
        try {
            var acl = client.getBucketAcl(request -> request.bucket(bucket));
            // 部分服务不提供所有者编号；以实际授权条目和桶策略判断，不能把显示信息作为权限依据。
            if (acl.grants().isEmpty() || acl.grants().stream()
                .anyMatch(grant -> grant.grantee() == null || grant.grantee().uri() != null
                    || !Set.of(Type.CANONICAL_USER, Type.AMAZON_CUSTOMER_BY_EMAIL).contains(grant.grantee().type()))) {
                throw FileStorageKeys.storageUnavailable();
            }
            String policy;
            try {
                policy = client.getBucketPolicy(request -> request.bucket(bucket)).policy();
            } catch (S3Exception missing) {
                if (missing.awsErrorDetails() != null && "NoSuchBucketPolicy".equals(
                    missing.awsErrorDetails().errorCode())) {
                    return;
                }
                throw missing;
            }
            if (policy == null || policy.length() > 262144) {
                throw FileStorageKeys.storageUnavailable();
            }
            var statements = json.readTree(policy).path("Statement");
            if (statements.isObject()) {
                check(statements);
            } else if (statements.isArray()) {
                statements.forEach(this::check);
            } else {
                throw FileStorageKeys.storageUnavailable();
            }
        } catch (IOException | RuntimeException unavailable) {
            throw FileStorageKeys.storageUnavailable();
        }
    }

    private void check(JsonNode statement) {
        if (!statement.isObject() || !Set.of("Allow", "Deny").contains(statement.path("Effect").asText())) {
            throw FileStorageKeys.storageUnavailable();
        }
        if (statement.path("Effect").asText().equals("Allow") && (statement.has("NotPrincipal") || wildcard(
            statement.path("Principal")))) {
            throw FileStorageKeys.storageUnavailable();
        }
    }

    private boolean wildcard(JsonNode principal) {
        if (principal.isTextual()) {
            return principal.asText().contains("*");
        }
        if (principal.isContainerNode()) {
            for (var child : principal) {
                if (wildcard(child)) {
                    return true;
                }
            }
        }
        return principal.isMissingNode() || principal.isNull();
    }
}
