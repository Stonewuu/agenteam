package com.stonewu.agenteam.service.modelpricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceBookMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceStore;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceVersionMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceBook;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceVersionRow;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelpricing.response.ModelPriceVersionView;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同一模型的价格修改按模型记录加锁，发布后只能复制为新草稿。
 */
@Service
public class ModelPriceService {
    private final ModelPriceStore store;
    private final ModelPriceBookMapper mapping;
    private final ModelPriceVersionMapper versions;
    private final ModelProfileSqlMapper models;
    private final ModelPriceValidation validation;
    private final EnterpriseAuthorizationService authorization;
    private final Clock clock;

    public ModelPriceService(ModelPriceStore store, ModelPriceBookMapper mapping, ModelPriceVersionMapper versions,
                             ModelProfileSqlMapper models, ModelPriceValidation validation,
                             EnterpriseAuthorizationService authorization, Clock clock) {
        this.store = store;
        this.mapping = mapping;
        this.versions = versions;
        this.models = models;
        this.validation = validation;
        this.authorization = authorization;
        this.clock = clock;
    }

    public void authorize(AuthContext actor, boolean mutation) {
        authorization.requireEnterpriseScope(mutation ? authorization.lockAndRequire(actor, "model.manage")
            : authorization.require(actor, "model.manage"));
    }

    public List<ModelPriceVersionView> list(AuthContext actor, String model, Long before) {
        authorize(actor, false);
        requireModel(actor, model, false);
        return store.list(actor.enterpriseId(), model, before, 50).stream()
            .map(row -> mapping.view(new ModelPriceBook(row, List.of()), false)).toList();
    }

    public ModelPriceVersionView detail(AuthContext actor, String model, String id) {
        authorize(actor, false);
        return mapping.view(store.load(store.version(actor.enterpriseId(), model, id)), true);
    }

    public ModelPriceVersionView current(AuthContext actor, String model) {
        authorize(actor, false);
        requireModel(actor, model, false);
        var row = store.current(actor.enterpriseId(), model, clock.instant());
        return row == null ? null : mapping.view(store.load(row), true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModelPriceVersionView create(AuthContext actor, String model, ModelPriceConfiguration input) {
        authorize(actor, true);
        requireModel(actor, model, true);
        return createDraft(actor, model, validation.normalize(input));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModelPriceVersionView copy(AuthContext actor, String model, String id) {
        authorize(actor, true);
        requireModel(actor, model, true);
        var original = store.load(store.version(actor.enterpriseId(), model, id));
        return createDraft(actor, model, mapping.configuration(original));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModelPriceVersionView update(AuthContext actor, String model, String id, long revision, ModelPriceConfiguration input) {
        authorize(actor, true);
        requireModel(actor, model, true);
        var row = draft(actor, model, id, revision);
        var config = validation.normalize(input);
        int changed = versions.update(Wrappers.<ModelPriceVersionRow>lambdaUpdate()
            .eq(ModelPriceVersionRow::getEnterpriseId, actor.enterpriseId()).eq(ModelPriceVersionRow::getId, id)
            .eq(ModelPriceVersionRow::getStatus, "draft").eq(ModelPriceVersionRow::getRevision, revision)
            .set(ModelPriceVersionRow::getCurrency, config.currency()).set(ModelPriceVersionRow::getTimezone, config.timezone())
            .set(ModelPriceVersionRow::getCalendarValidThrough, config.calendarValidThrough())
            .set(ModelPriceVersionRow::getSourceReference, config.sourceReference()).set(ModelPriceVersionRow::getSourceCheckedAt, config.sourceCheckedAt())
            .set(ModelPriceVersionRow::getNote, config.note()).set(ModelPriceVersionRow::getUpdatedAt, clock.instant())
            .setIncrBy(ModelPriceVersionRow::getRevision, 1));
        checkChanged(changed, revision);
        store.deleteRules(actor.enterpriseId(), id);
        store.insertRules(mapping.create(row, config));
        audit(actor, "update", id, "修改价格草稿");
        return detail(actor, model, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModelPriceVersionView publish(AuthContext actor, String model, String id, long revision) {
        authorize(actor, true);
        requireModel(actor, model, true);
        var row = draft(actor, model, id, revision);
        validation.requirePublishable(mapping.configuration(store.load(row)));
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var previous = store.current(actor.enterpriseId(), model, now);
        if (previous != null && !now.isAfter(previous.getEffectiveFrom())) {
            // 不伪造未来生效时间；同毫秒的极少数重复操作由调用方重新提交。
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_PUBLICATION_TOO_SOON", "价格刚刚更新，请稍后重新发布。");
        }
        checkChanged(versions.update(Wrappers.<ModelPriceVersionRow>lambdaUpdate()
            .eq(ModelPriceVersionRow::getEnterpriseId, actor.enterpriseId()).eq(ModelPriceVersionRow::getId, id)
            .eq(ModelPriceVersionRow::getStatus, "draft").eq(ModelPriceVersionRow::getRevision, revision)
            .set(ModelPriceVersionRow::getStatus, "published").set(ModelPriceVersionRow::getEffectiveFrom, now)
            .set(ModelPriceVersionRow::getPublishedBy, actor.userId()).set(ModelPriceVersionRow::getPublishedAt, now)
            .set(ModelPriceVersionRow::getUpdatedAt, now).setIncrBy(ModelPriceVersionRow::getRevision, 1)), revision);
        audit(actor, "publish", id, "发布模型价格");
        return detail(actor, model, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AuthContext actor, String model, String id, long revision) {
        authorize(actor, true);
        requireModel(actor, model, true);
        draft(actor, model, id, revision);
        store.deleteRules(actor.enterpriseId(), id);
        checkChanged(versions.delete(Wrappers.<ModelPriceVersionRow>lambdaQuery()
            .eq(ModelPriceVersionRow::getEnterpriseId, actor.enterpriseId()).eq(ModelPriceVersionRow::getId, id)
            .eq(ModelPriceVersionRow::getStatus, "draft").eq(ModelPriceVersionRow::getRevision, revision)), revision);
        audit(actor, "delete", id, "删除价格草稿");
    }

    private ModelPriceVersionView createDraft(AuthContext actor, String model, ModelPriceConfiguration config) {
        var latest = store.list(actor.enterpriseId(), model, null, 1);
        var row = new ModelPriceVersionRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(actor.enterpriseId());
        row.setModelProfileId(model);
        row.setVersionNo(latest.isEmpty() ? 1 : Math.addExact(latest.getFirst().getVersionNo(), 1));
        row.setStatus("draft");
        row.setContextBasis("total_input");
        row.setRevision(1L);
        row.setCreatedBy(actor.userId());
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(row.getCreatedAt());
        var book = mapping.create(row, config);
        store.insert(book);
        audit(actor, "create", row.getId(), "创建价格草稿");
        return mapping.view(book, true);
    }

    private ModelPriceVersionRow draft(AuthContext actor, String model, String id, long revision) {
        var row = store.version(actor.enterpriseId(), model, id);
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        if (!row.getStatus().equals("draft")) {
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_VERSION_IMMUTABLE", "已发布价格不能修改，请复制后创建新版本。");
        }
        return row;
    }

    private void requireModel(AuthContext actor, String model, boolean lock) {
        var row = lock ? models.lockForPricing(actor.enterpriseId(), model) : models.selectOne(
            Wrappers.<ModelProfileRow>lambdaQuery().eq(ModelProfileRow::getEnterpriseId, actor.enterpriseId())
                .eq(ModelProfileRow::getId, model));
        if (row == null) {
            throw ResourceAuthorizationService.unavailable();
        }
    }

    private void checkChanged(int changed, long revision) {
        if (changed != 1) {
            throw ApiException.versionConflict(revision);
        }
    }

    private void audit(AuthContext actor, String action, String id, String label) {
        authorization.changed(actor, "model_price." + action, "model_price_version", id, label, Map.of());
    }
}
