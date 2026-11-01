package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.RunQuestionMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.request.QuestionAnswerRequest;
import com.stonewu.agenteam.model.execution.response.RunQuestionView;
import com.stonewu.agenteam.service.execution.RunQuestionService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;

/** 平台输入只决定回答内容，问题所属用户、任务与机器人仍从服务端记录核对。 */
@Service
public class ChatQuestionReplyService {
    private final RunQuestionMapper rows;
    private final RunQuestionService questions;
    private final ChatOutboundMessageMapper messages;
    private final ChatPayloadCipher cipher;
    private final Clock clock;

    public ChatQuestionReplyService(RunQuestionMapper rows, RunQuestionService questions,
            ChatOutboundMessageMapper messages, ChatPayloadCipher cipher, Clock clock) {
        this.rows = rows;
        this.questions = questions;
        this.messages = messages;
        this.cipher = cipher;
        this.clock = clock;
    }

    public RunQuestionView card(AuthContext actor, ChatOutboundMessageRow source, JsonNode payload, String action, String text) {
        var question = requireCardQuestion(actor, source, payload);
        if ("question_free_text".equals(action)) {
            questions.selectFreeText(actor, question.getId());
            return null;
        }
        String option = null;
        if (action.startsWith("question_option_")) {
            option = action.substring("question_".length());
        } else if (!"question_submit".equals(action)) {
            throw unavailable();
        }
        return questions.answer(actor, question.getId(), payload.path("userQuestion").path("revision").asLong(-1),
            new QuestionAnswerRequest(option, text));
    }

    public RunQuestionRow requireCardQuestion(AuthContext actor, ChatOutboundMessageRow source, JsonNode payload) {
        if (!"user_question_card".equals(source.getKind()) || !payload.path("questionCardId").asText().equals(source.getId())
                || !payload.path("replyFull").asBoolean()) {
            throw unavailable();
        }
        return sourceQuestion(actor, source, payload.path("userQuestion"));
    }

    public boolean answerText(AuthContext actor, String taskId, String botId, ChatMessageRow message, String text) {
        if (message.getReplyToPlatformId() != null) {
            var source = messages.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>()
                .eq(ChatOutboundMessageRow::getEnterpriseId, actor.enterpriseId()).eq(ChatOutboundMessageRow::getTaskId, taskId)
                .eq(ChatOutboundMessageRow::getConversationId, message.getConversationId()).eq(ChatOutboundMessageRow::getBotBindingId, botId)
                .eq(ChatOutboundMessageRow::getPlatformMessageId, message.getReplyToPlatformId())
                .in(ChatOutboundMessageRow::getKind, "user_question_card", "user_question_text").eq(ChatOutboundMessageRow::getStatus, "sent"));
            if (source != null) {
                if (source.getRenderedPayloadEncrypted() == null) {
                    throw unavailable();
                }
                var payload = cipher.decrypt(actor.enterpriseId(), "outbound-rendered", source.getId(), source.getRenderedPayloadEncrypted());
                if (!payload.path("replyFull").asBoolean()) {
                    throw unavailable();
                }
                var reference = payload.path("user_question_card".equals(source.getKind()) ? "userQuestion" : "questionReference");
                if (reference.isMissingNode()) {
                    throw selectionRequired();
                }
                var question = sourceQuestion(actor, source, reference);
                questions.answer(actor, question.getId(), reference.path("revision").asLong(-1), new QuestionAnswerRequest(null, text));
                return true;
            }
        }
        var pending = rows.selectList(pending(actor, message.getConversationId(), botId, message.getReceivedAt())
            .eq(RunQuestionRow::getTaskId, taskId));
        if (pending.isEmpty()) {
            return false;
        }
        RunQuestionRow selected = selected(pending, message.getReceivedAt());
        if (selected == null) {
            if (pending.size() != 1) {
                throw selectionRequired();
            }
            selected = pending.getFirst();
        }
        questions.answer(actor, selected.getId(), selected.getRevision(), new QuestionAnswerRequest(null, text));
        return true;
    }

    /** 飞书群聊中只有一个待回答任务时，直接提及机器人即可继续回答。 */
    public Optional<String> pendingTask(AuthContext actor, String conversation, String bot, Instant received) {
        var pending = rows.selectList(pending(actor, conversation, bot, received));
        var selected = selected(pending, received);
        if (selected != null) {
            return Optional.of(selected.getTaskId());
        }
        var tasks = pending.stream().map(RunQuestionRow::getTaskId).distinct().toList();
        if (tasks.size() > 1) {
            throw selectionRequired();
        }
        return tasks.stream().findFirst();
    }

    private LambdaQueryWrapper<RunQuestionRow> pending(AuthContext actor, String conversation, String bot, Instant received) {
        return new LambdaQueryWrapper<RunQuestionRow>()
            .eq(RunQuestionRow::getEnterpriseId, actor.enterpriseId()).eq(RunQuestionRow::getUserId, actor.userId())
            .eq(RunQuestionRow::getChatConversationId, conversation).eq(RunQuestionRow::getBotBindingId, bot)
            .eq(RunQuestionRow::getStatus, "pending").le(RunQuestionRow::getCreatedAt, received)
            .gt(RunQuestionRow::getExpiresAt, clock.instant()).isNotNull(RunQuestionRow::getTaskId)
            .orderByAsc(RunQuestionRow::getCreatedAt, RunQuestionRow::getId);
    }

    private RunQuestionRow selected(List<RunQuestionRow> pending, Instant received) {
        return pending.stream().filter(row -> row.getFreeTextSelectedAt() != null && !row.getFreeTextSelectedAt().isAfter(received))
            .max(Comparator.comparing(RunQuestionRow::getFreeTextSelectedAt)).orElse(null);
    }

    private RunQuestionRow sourceQuestion(AuthContext actor, ChatOutboundMessageRow source, JsonNode reference) {
        // 此处只核对消息归属；回答或选择输入的服务会统一完成权限与当前执行状态校验。
        var question = rows.find(actor.enterpriseId(), reference.path("id").asText())
            .filter(row -> actor.userId().equals(row.getUserId())).orElseThrow(ResourceAuthorizationService::unavailable);
        if (!source.getTaskId().equals(question.getTaskId()) || !source.getBotBindingId().equals(question.getBotBindingId())
                || !source.getConversationId().equals(question.getChatConversationId())) {
            throw unavailable();
        }
        return question;
    }

    private static ApiException selectionRequired() {
        return new ApiException(HttpStatus.CONFLICT, "QUESTION_SELECTION_REQUIRED", "请回复要回答的问题消息，或先点击该问题的“自己输入”。");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "QUESTION_UNAVAILABLE", "这个问题已结束或无法访问，请继续当前对话。");
    }
}
