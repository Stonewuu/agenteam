package com.stonewu.agenteam.configuration.plugin;

import com.stonewu.agenteam.mapper.plugin.BuiltinPluginResourceMapper;
import com.stonewu.agenteam.service.plugin.BuiltinPluginResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时为已有企业补齐内置插件；每个企业独立提交，重复启动不会重复发布。
 */
@Component
public class BuiltinPluginResourceInitialization implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BuiltinPluginResourceInitialization.class);

    private final BuiltinPluginResourceMapper selection;

    private final BuiltinPluginResourceService resources;

    public BuiltinPluginResourceInitialization(BuiltinPluginResourceMapper selection,
                                               BuiltinPluginResourceService resources) {
        this.selection = selection;
        this.resources = resources;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String after = null;
        while (true) {
            var enterpriseIds = selection.enterprisesAfter(after);
            if (enterpriseIds.isEmpty()) {
                return;
            }
            for (String enterprise : enterpriseIds) {
                try {
                    resources.initialize(enterprise);
                } catch (RuntimeException failure) {
                    LOG.error("初始化内置插件失败，企业编号 {}", enterprise, failure);
                    throw failure;
                }
            }
            after = enterpriseIds.getLast();
        }
    }
}
