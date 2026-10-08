package com.stonewu.agenteam.configuration.plugin;

import com.stonewu.agenteam.mapper.plugin.PlatformToolDefinitions;
import com.stonewu.agenteam.service.plugin.PlatformBuiltinPluginAdapter;
import com.stonewu.agenteam.service.plugin.PlatformBusinessTools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformBuiltinPlugins {

    @Bean
    public PlatformBuiltinPluginAdapter platformBasics(PlatformToolDefinitions tools,
                                                       ObjectProvider<PlatformBusinessTools> business) {
        return new PlatformBuiltinPluginAdapter("platform_basics", "平台基础",
            "读取当前企业和时间，搜索本人有权访问的工作空间内容。", tools.basics(), business);
    }

    @Bean
    public PlatformBuiltinPluginAdapter todoManagement(PlatformToolDefinitions tools,
                                                       ObjectProvider<PlatformBusinessTools> business) {
        return new PlatformBuiltinPluginAdapter("todo_management", "待办管理",
            "查询、创建和维护待办，选择负责人、修改状态与查看历史。修改前需确认。", tools.todos(), business);
    }

    @Bean
    public PlatformBuiltinPluginAdapter scheduleManagement(PlatformToolDefinitions tools,
                                                           ObjectProvider<PlatformBusinessTools> business) {
        return new PlatformBuiltinPluginAdapter("schedule_management", "定时任务",
            "查询和管理本人的执行计划，预览未来时间，启停计划或执行一次。修改前需确认。", tools.schedules(), business);
    }
}
