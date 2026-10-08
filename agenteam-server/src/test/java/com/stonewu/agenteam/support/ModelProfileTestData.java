package com.stonewu.agenteam.support;

import com.stonewu.agenteam.mapper.modelprofile.ModelProfileMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.model.modelprofile.request.ModelProviderWriteRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 仅供隔离测试准备模型；不读取部署文件或实际环境变量。
 */
@Component
public class ModelProfileTestData {
    private final ModelProfileMapper profiles;
    private final ModelProviderMapper providers;
    private final Clock clock;

    public ModelProfileTestData(ModelProfileMapper profiles, ModelProviderMapper providers, Clock clock) {
        this.profiles = profiles;
        this.providers = providers;
        this.clock = clock;
    }

    @Transactional
    public List<String> saveProfiles(List<ModelProfileFixture> entries, Function<String, String> secrets) {
        List<String> ids = new ArrayList<>();
        for (var entry : entries) {
            String id = UUID.nameUUIDFromBytes((entry.enterpriseId() + ":" + entry.code() + ":" + entry.versionNo()).getBytes(StandardCharsets.UTF_8)).toString();
            String providerId = "provider-" + id;
            var provider = new ModelProviderWriteRequest(entry.name() + "-" + entry.code(), entry.provider(), entry.baseUrl(),
                entry.secretEnvironment() == null ? "" : secrets.apply(entry.secretEnvironment()), true);
            var capability = entry.capabilities();
            var model = new ModelProfileWriteRequest(providerId, entry.name(), entry.modelName(), new ModelCapabilities(capability.supportsTools(), capability.supportsTemperature(),
                capability.maxOutputTokens(), capability.maxContextTokens(), capability.inputTypes().stream().sorted().toList(), capability.reasoningEfforts()), entry.enabled());
            if (profiles.find(entry.enterpriseId(), id).isEmpty()) {
                providers.insert(entry.enterpriseId(), providerId, provider, clock.instant());
                profiles.insert(entry.enterpriseId(), id, model, clock.instant());
            } else {
                providers.update(entry.enterpriseId(), providerId, provider, clock.instant());
                profiles.update(entry.enterpriseId(), id, model, clock.instant());
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }
}
