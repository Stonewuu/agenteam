package com.stonewu.agenteam.service.modelusage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceBookMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceRateMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceRuleDateMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceRuleMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceRuleTimeMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceStore;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceVersionMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileSqlMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageCostItemMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageEvidenceMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRecordMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRegistrationMapper;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.model.modelprofile.request.ModelProviderWriteRequest;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageCostItemRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageSnapshot;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.modelpricing.ModelPriceCalculator;
import com.stonewu.agenteam.service.modelpricing.ModelPriceService;
import com.stonewu.agenteam.service.modelpricing.ModelPriceValidation;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ModelBillingPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Test
    void repairsLegacyProtocolFactsOnceAndKeepsTheOriginalPriceDecision() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var f = new Fixture(database.databaseAccess());
            f.tx.executeWithoutResult(status -> f.usage.register("old-unpriced", "process", f.context, f.model, f.provider));
            var draft = f.tx.execute(status -> f.pricing.create(f.actor, f.model.id(), f.price("2")));
            var original = f.tx.execute(status -> f.pricing.publish(f.actor, f.model.id(), draft.id(), 1));
            for (String id : List.of("old-priced", "old-cancelled", "old-missing-read")) {
                f.tx.executeWithoutResult(status -> f.usage.register(id, "process", f.context, f.model, f.provider));
            }
            for (String id : List.of("old-unpriced", "old-priced", "old-cancelled", "old-missing-read")) {
                var collector = new ModelUsageAccumulator("openai", "standard", Set.of());
                collector.accept(f.json.readTree(id.equals("old-missing-read")
                    ? "{\"prompt_tokens\":1000,\"completion_tokens\":50}"
                    : "{\"prompt_tokens\":1000,\"completion_tokens\":50,\"prompt_tokens_details\":{\"cached_tokens\":0}}"));
                collector.finish();
                String callStatus = id.equals("old-cancelled") ? "cancelled" : "completed";
                f.tx.executeWithoutResult(status -> f.usage.save(f.snapshot(id, collector, callStatus)));
                var states = new HashMap<>(collector.snapshot().usage().fieldStates());
                states.put("cache_write", "not_reported");
                states.put("input_uncached", "not_reported");
                f.rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate().eq(ModelUsageRecordRow::getEnterpriseId, f.enterprise)
                    .eq(ModelUsageRecordRow::getId, id).set(ModelUsageRecordRow::getPricingAlgorithmVersion, 1)
                    .set(ModelUsageRecordRow::getUncachedInputTokens, null).set(ModelUsageRecordRow::getUsageStatus, "partial")
                    .set(ModelUsageRecordRow::getFieldStatesJson, f.json.writeValueAsString(states)));
            }
            var repairs = new ModelUsageProtocolRepairService(f.rows, f.items, new ModelUsageEvidenceMapper(f.json), Clock.fixed(NOW, ZoneOffset.UTC));
            var candidates = repairs.candidates();
            assertThat(candidates).hasSize(4);
            for (var candidate : candidates) {
                f.tx.executeWithoutResult(status -> repairs.repair(candidate));
            }
            for (var work : f.tx.execute(status -> f.recovery.claimCalculations())) {
                f.tx.executeWithoutResult(status -> f.recovery.calculate(work));
            }
            var charged = f.rows.selectById("old-priced");
            assertThat(charged.getUsageStatus()).isEqualTo("complete");
            assertThat(charged.getPriceStatus()).isEqualTo("priced");
            assertThat(charged.getUncachedInputTokens()).isEqualTo(1000);
            assertThat(charged.getTotalAmount()).isEqualByComparingTo("0.0024");
            assertThat(charged.getPriceVersionId()).isEqualTo(original.id());
            assertThat(f.rows.selectById("old-unpriced").getUsageStatus()).isEqualTo("complete");
            assertThat(f.rows.selectById("old-unpriced").getPriceVersionId()).isNull();
            assertThat(f.rows.selectById("old-unpriced").getReasonCode()).isEqualTo("PRICE_NOT_CONFIGURED");
            assertThat(f.rows.selectById("old-cancelled").getUsageStatus()).isEqualTo("partial");
            assertThat(f.rows.selectById("old-missing-read").getUsageStatus()).isEqualTo("partial");
            assertThat(repairs.candidates()).isEmpty();
            f.tx.executeWithoutResult(status -> repairs.repair(new ModelUsageProtocolRepairService.Candidate(f.enterprise, "old-priced")));
            assertThat(f.rows.selectById("old-priced").getRevision()).isEqualTo(charged.getRevision());
            assertThat(f.items.selectCount(Wrappers.<ModelUsageCostItemRow>lambdaQuery()
                .eq(ModelUsageCostItemRow::getUsageRecordId, "old-priced"))).isEqualTo(2);
        }
    }

    @Test
    void upgradesExistingModelsAndRepeatedStartupDoesNotChangeThem() throws Exception {
        try (var database = new IsolatedDatabase()) {
            var access = database.databaseAccess();
            Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.2.0.1").cleanDisabled(true).load().migrate();
            var fixture = new Fixture(access);
            var upgrade = Flyway.configure().dataSource(access.getDataSource()).locations("classpath:db/schema")
                .target("0.3.0.2").cleanDisabled(true).validateMigrationNaming(true).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(fixture.providers.find(fixture.enterprise, fixture.provider.id()).orElseThrow().protocol()).isEqualTo("openai");
            assertThat(fixture.rows.selectCount(Wrappers.<ModelUsageRecordRow>lambdaQuery())).isZero();
            for (String protocol : List.of("openai_responses", "anthropic", "gemini")) {
                fixture.providers.insert(fixture.enterprise, UUID.randomUUID().toString(), new ModelProviderWriteRequest(protocol,
                    protocol, "https://example.com", "test", true), NOW);
            }
        }
    }

    @Test
    void snapshotsAreIdempotentAndPriceIsFrozenBeforeTheNetworkRequest() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var f = new Fixture(database.databaseAccess());
            f.tx.executeWithoutResult(status -> f.usage.register("without-price", "process", f.context, f.model, f.provider));
            var price = f.tx.execute(status -> f.pricing.create(f.actor, f.model.id(), f.price("2")));
            var published = f.tx.execute(status -> f.pricing.publish(f.actor, f.model.id(), price.id(), 1));
            assertThat(published.status()).isEqualTo("published");
            f.tx.executeWithoutResult(status -> f.usage.register("with-price", "process", f.context, f.model, f.provider));
            var collector = new ModelUsageAccumulator("openai", "standard", Set.of());
            collector.accept(f.json.readTree("{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0}}"));
            var intermediate = f.snapshot("with-price", collector, "in_flight");
            assertThat(f.tx.<Boolean>execute(status -> f.usage.save(intermediate))).isTrue();
            collector.accept(f.json.readTree("{\"completion_tokens\":50}"));
            collector.finish();
            var complete = f.snapshot("with-price", collector, "completed");
            assertThat(f.tx.<Boolean>execute(status -> f.usage.save(complete))).isTrue();
            assertThat(f.tx.<Boolean>execute(status -> f.usage.save(complete))).isFalse();
            assertThat(f.tx.<Boolean>execute(status -> f.usage.save(intermediate))).isFalse();
            f.tx.executeWithoutResult(status -> f.usage.save(f.snapshot("without-price", collector, "completed")));
            var work = f.tx.execute(status -> f.recovery.claimCalculations());
            assertThat(work).hasSize(2);
            for (var item : work) {
                f.tx.executeWithoutResult(status -> f.recovery.calculate(item));
                f.tx.executeWithoutResult(status -> f.recovery.calculate(item));
            }
            var charged = f.rows.selectById("with-price");
            assertThat(charged.getTotalAmount()).isEqualByComparingTo("0.0024");
            assertThat(charged.getOutputTokens()).isEqualTo(50);
            assertThat(charged.getPriceVersionId()).isEqualTo(published.id());
            assertThat(f.items.selectCount(Wrappers.<ModelUsageCostItemRow>lambdaQuery().eq(ModelUsageCostItemRow::getUsageRecordId, "with-price"))).isEqualTo(2);
            assertThat(f.rows.selectById("without-price").getPriceStatus()).isEqualTo("unpriced");
            assertThat(f.rows.selectById("without-price").getTotalAmount()).isNull();
            assertThatThrownBy(() -> f.tx.execute(status -> f.pricing.update(f.actor, f.model.id(), price.id(), 2, f.price("9"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("不能修改");
            var copy = f.tx.execute(status -> f.pricing.copy(f.actor, f.model.id(), price.id()));
            assertThat(copy.id()).isNotEqualTo(price.id());
            assertThat(copy.status()).isEqualTo("draft");
            assertThatThrownBy(() -> f.tx.execute(status -> f.pricing.update(f.actor, f.model.id(), copy.id(), 20, f.price("9"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("更新");
        }
    }

    @Test
    void lostProcessIsMarkedInterruptedButLateAuthoritativeUsageIsStillSaved() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var f = new Fixture(database.databaseAccess());
            f.tx.executeWithoutResult(status -> f.usage.register("lost", "process", f.context, f.model, f.provider));
            f.rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate().eq(ModelUsageRecordRow::getId, "lost")
                .set(ModelUsageRecordRow::getRequestLeaseUntil, NOW.minusSeconds(1)));
            f.tx.executeWithoutResult(status -> f.recovery.recoverInterrupted());
            assertThat(f.rows.selectById("lost").getCallStatus()).isEqualTo("interrupted");
            var collector = new ModelUsageAccumulator("openai", "standard", Set.of());
            collector.accept(f.json.readTree("{\"prompt_tokens\":5,\"completion_tokens\":10}"));
            assertThat(f.tx.<Boolean>execute(status -> f.usage.save(f.snapshot("lost", collector, "in_flight")))).isFalse();
            collector.finish();
            assertThat(f.tx.<Boolean>execute(status -> f.usage.save(f.snapshot("lost", collector, "completed")))).isTrue();
            assertThat(f.rows.selectById("lost").getOutputTokens()).isEqualTo(10);
            assertThat(f.rows.selectById("lost").getRunLeaseVersion()).isEqualTo(7);
        }
    }

    @Test
    void databaseRejectsCrossEnterpriseLinksAndWrongSaveOwner() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var f = new Fixture(database.databaseAccess());
            var second = new Fixture(database.databaseAccess());
            var row = new ModelUsageRegistrationMapper().create("invalid-owner", "process", f.context, f.model, f.provider, null, NOW);
            row.setEnterpriseId(second.enterprise);
            assertThatThrownBy(() -> f.rows.insert(row)).isInstanceOf(DataIntegrityViolationException.class);
            f.tx.executeWithoutResult(status -> f.usage.register("scoped", "process", f.context, f.model, f.provider));
            var collector = new ModelUsageAccumulator("openai", "standard", Set.of());
            collector.finish();
            var wrong = new ModelUsageSnapshot(f.enterprise, "scoped", "other-process", collector.snapshot(), "completed", NOW, null, null, null, null);
            assertThat(f.tx.<Boolean>execute(status -> f.usage.save(wrong))).isFalse();
            assertThatThrownBy(() -> f.pricing.list(second.actor, f.model.id(), null)).isInstanceOf(ApiException.class);
        }
    }

    private static class Fixture {
        final ObjectMapper json = new ObjectMapper();
        final String enterprise = UUID.randomUUID().toString();
        final TransactionTemplate tx;
        final ModelProviderMapper providers;
        final ModelUsageRecordMapper rows;
        final ModelUsageCostItemMapper items;
        final ModelProfileRecord model;
        final ModelProviderRecord provider;
        final ModelCallContext context;
        final AuthContext actor;
        final ModelUsageService usage;
        final ModelPriceService pricing;
        final ModelUsageRecoveryService recovery;

        Fixture(MybatisTestDatabase db) throws Exception {
            tx = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
            var user = new AppUserRow();
            user.setId(UUID.randomUUID().toString());
            user.setUsername("billing_" + user.getId());
            user.setUsernameNormalized(user.getUsername());
            user.setPasswordHash("isolated-test-only");
            user.setDisplayName("用量测试用户");
            db.mapper(AppUserTableMapper.class).insert(user);
            var company = new EnterpriseRow();
            company.setId(enterprise);
            company.setName("计费测试企业");
            company.setCreatedBy(user.getId());
            company.setCreatedAt(NOW);
            company.setUpdatedAt(NOW);
            company.setQuotaPeriodStart(NOW);
            company.setQuotaPeriodEnd(NOW.plusSeconds(86400));
            db.mapper(EnterpriseTableMapper.class).insert(company);
            actor = new AuthContext(new UserEntity(user.getId(), user.getUsername(), "", "测试用户", "active", false, enterprise,
                null, null, 1, NOW, 1, NOW, NOW), enterprise, Set.of("model.manage"));
            providers = db.mapper(ModelProviderMapper.class);
            String providerId = UUID.randomUUID().toString();
            providers.insert(enterprise, providerId, new ModelProviderWriteRequest("测试连接", "openai", "https://example.com", "not-a-real-key", true), NOW);
            provider = providers.find(enterprise, providerId).orElseThrow();
            var profiles = new ModelProfileMapper(db.mapper(ModelProfileSqlMapper.class), json);
            String modelId = UUID.randomUUID().toString();
            profiles.insert(enterprise, modelId, new ModelProfileWriteRequest(providerId, "测试模型", "test-model",
                new ModelCapabilities(true, true, 1024, 8000, List.of("text")), true), NOW);
            model = profiles.find(enterprise, modelId).orElseThrow();
            context = new ModelCallContext(enterprise, user.getId(), "conversation", "run", 2, 7, "agent", "session", "node", "schedule", "schedule", "workflow");
            rows = db.mapper(ModelUsageRecordMapper.class);
            items = db.mapper(ModelUsageCostItemMapper.class);
            var store = new ModelPriceStore(db.mapper(ModelPriceVersionMapper.class), db.mapper(ModelPriceRuleMapper.class),
                db.mapper(ModelPriceRuleTimeMapper.class), db.mapper(ModelPriceRuleDateMapper.class), db.mapper(ModelPriceRateMapper.class));
            var clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var evidence = new ModelUsageEvidenceMapper(json);
            usage = new ModelUsageService(rows, store, new ModelUsageRegistrationMapper(), evidence, clock, mock(ModelUsageRegistrationGuard.class));
            pricing = new ModelPriceService(store, new ModelPriceBookMapper(), db.mapper(ModelPriceVersionMapper.class), db.mapper(ModelProfileSqlMapper.class),
                new ModelPriceValidation(), mock(EnterpriseAuthorizationService.class), clock);
            recovery = new ModelUsageRecoveryService(rows, items, store, evidence, new ModelPriceCalculator(), clock);
        }

        ModelUsageSnapshot snapshot(String id, ModelUsageAccumulator collector, String status) {
            return new ModelUsageSnapshot(enterprise, id, "process", collector.snapshot(), status, NOW, 200, "req-test", null, null);
        }

        ModelPriceConfiguration price(String amount) {
            return new ModelPriceConfiguration("USD", "UTC", null, null, null, null, List.of(new ModelPriceConfiguration.Rule(
                "基础", 0, true, 0, null, "standard", List.of(), List.of(), List.of(
                new ModelPriceConfiguration.Rate("input_uncached", 0, new BigDecimal(amount)),
                new ModelPriceConfiguration.Rate("output", 0, new BigDecimal("8"))))));
        }
    }
}
