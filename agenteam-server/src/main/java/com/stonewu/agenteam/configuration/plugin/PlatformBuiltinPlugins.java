package com.stonewu.agenteam.configuration.plugin;

import com.stonewu.agenteam.mapper.plugin.PlatformToolDefinitions;
import com.stonewu.agenteam.mapper.collaboration.CollaborationToolDefinitions;
import com.stonewu.agenteam.mapper.workflow.WorkflowToolDefinitions;
import com.stonewu.agenteam.service.plugin.PlatformBuiltinPluginAdapter;
import com.stonewu.agenteam.service.plugin.PlatformBusinessTools;
import com.stonewu.agenteam.service.plugin.BuiltinPluginPresentation;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformBuiltinPlugins {

    @Bean
    public PlatformBuiltinPluginAdapter workflowManagement(WorkflowToolDefinitions tools,
                                                            ObjectProvider<PlatformBusinessTools> business) {
        return create("workflow_management", tools.tools(), business);
    }

    @Bean
    public PlatformBuiltinPluginAdapter groupCollaboration(CollaborationToolDefinitions tools,
                                                           ObjectProvider<PlatformBusinessTools> business) {
        return create("group_collaboration", tools.tools(), business);
    }

    @Bean
    public PlatformBuiltinPluginAdapter platformBasics(PlatformToolDefinitions tools,
                                                       ObjectProvider<PlatformBusinessTools> business) {
        return create("platform_basics", tools.basics(), business);
    }

    @Bean
    public PlatformBuiltinPluginAdapter todoManagement(PlatformToolDefinitions tools,
                                                       ObjectProvider<PlatformBusinessTools> business) {
        return create("todo_management", tools.todos(), business);
    }

    @Bean
    public PlatformBuiltinPluginAdapter scheduleManagement(PlatformToolDefinitions tools,
                                                           ObjectProvider<PlatformBusinessTools> business) {
        return create("schedule_management", tools.schedules(), business);
    }

    private PlatformBuiltinPluginAdapter create(String code, List<ToolDefinition> tools,
                                                 ObjectProvider<PlatformBusinessTools> business) {
        return new PlatformBuiltinPluginAdapter(code, BuiltinPluginPresentation.name(code),
            BuiltinPluginPresentation.description(code), tools, business);
    }
}
