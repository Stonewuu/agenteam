package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.execution.entity.ConversationTitleInput;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.service.agent.AgentModelFactory;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 只概括首条消息，不创建智能体，不调用工具，也不写入聊天历史。 */
@Component
public class ConversationTitleGenerator {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String INSTRUCTION = Messages.source("text.execution.conversationTitleGenerator.INSTRUCTION.1");
    private final AgentModelFactory models;
    private final ModelProfileCatalog profiles;

    public ConversationTitleGenerator(AgentModelFactory models, ModelProfileCatalog profiles) {
        this.models = models;
        this.profiles = profiles;
    }

    public String generate(ConversationTitleInput input, BooleanSupplier valid) {
        var profile = profiles.requireAvailable(input.context().enterpriseId(), input.modelProfileId());
        var model = models.create(input.context().enterpriseId(), input.modelProfileId());
        boolean noReasoning = profile.capabilities().reasoningEfforts().contains("none");
        String reasoning = noReasoning ? "none" : input.reasoningEffort();
        int limit = model.supportsReasoning() && !noReasoning ? model.maxOutputTokens() : Math.min(128, model.maxOutputTokens());
        var options = GenerateOptions.builder().maxTokens(limit).reasoningEffort(reasoning)
            .executionConfig(ExecutionConfig.builder().maxAttempts(1).timeout(TIMEOUT).build()).build();
        var messages = List.of(Msg.builder().name("system").role(MsgRole.SYSTEM).textContent(INSTRUCTION).build(),
            Msg.builder().name("user").role(MsgRole.USER).textContent(input.text()).build());
        if (!valid.getAsBoolean()) {
            throw new CancellationException(Messages.text("error.execution.conversationTitleGenerator.generate.1"));
        }
        var cancelled = Flux.interval(Duration.ofSeconds(1)).handle((tick, sink) -> {
            if (!valid.getAsBoolean()) {
                sink.error(new CancellationException(Messages.text("error.execution.conversationTitleGenerator.generate.1")));
            }
        });
        StringBuilder result = model.stream(messages, List.of(), options).takeUntilOther(cancelled)
            .collect(StringBuilder::new, (text, response) -> {
                for (var block : response.getContent()) {
                    if (block instanceof TextBlock part && part.getText() != null) {
                        text.append(part.getText());
                        if (text.length() > 2048) {
                            throw new IllegalStateException(Messages.text("error.execution.conversationTitleGenerator.generate.2"));
                        }
                    }
                }
            }).timeout(TIMEOUT).contextWrite(context -> context.put(ModelCallContext.class, input.context())).block();
        return clean(result == null ? "" : result.toString());
    }

    static String clean(String text) {
        String title = text.strip();
        if (title.length() >= 2 && (title.startsWith("\"") && title.endsWith("\"")
            || title.startsWith("“") && title.endsWith("”") || title.startsWith("'") && title.endsWith("'"))) {
            title = title.substring(1, title.length() - 1).strip();
        }
        if (title.isBlank() || title.codePointCount(0, title.length()) > 30
            || title.codePoints().anyMatch(Character::isISOControl)
            || title.indexOf('\u2028') >= 0 || title.indexOf('\u2029') >= 0) {
            return null;
        }
        return title.replaceAll(" +", " ");
    }
}
