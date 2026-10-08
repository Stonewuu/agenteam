package com.stonewu.agenteam.service.knowledge;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeRetentionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * 删除操作立即禁止访问，后台逐批清除文字和不再被资料引用的文件。
 */
@Service
public class KnowledgeRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeRetentionService.class);
    private final EnterpriseMapper enterprises;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeRetentionMapper retention;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    public KnowledgeRetentionService(EnterpriseMapper enterprises, KnowledgeDocumentMapper documents,
                                     KnowledgeRetentionMapper retention,
                                     TransactionTemplate transactions, Clock clock,
                                     @Value("${knowledge.retention.enabled:true}") boolean enabled) {
        this.enterprises = enterprises;
        this.documents = documents;
        this.retention = retention;
        this.transactions = transactions;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60000)
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            clean();
        } catch (RuntimeException failed) {
            LOG.warn("知识资料清理暂未完成，稍后重试", failed);
        }
    }

    public int clean() {
        int count = 0;
        for (var candidate : retention.candidates()) {
            Boolean removed = transactions.execute(status -> {
                enterprises.lockEnterprise(candidate.enterprise());
                var doc = documents.find(candidate.enterprise(), candidate.document(), true).orElse(null);
                return doc != null && doc.deletedAt() != null && retention.remove(doc, clock.instant());
            });
            if (Boolean.TRUE.equals(removed)) {
                count++;
            }
        }
        return count;
    }
}
