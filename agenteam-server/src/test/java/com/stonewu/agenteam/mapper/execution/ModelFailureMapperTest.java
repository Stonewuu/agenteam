package com.stonewu.agenteam.mapper.execution;

import io.agentscope.core.exception.CompositeAgentException;
import io.agentscope.core.exception.CompositeAgentException.AgentExceptionInfo;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelFailureMapperTest {
    @Test
    void mixedAgentFailuresMustNotTurnAnAuthenticationFailureIntoATemporaryRetry() {
        var temporary = OpenAIException.create(503, "原始服务错误", "test", "原始响应");
        var denied = OpenAIException.create(401, "原始认证错误", "test", "原始响应");
        var combined = new CompositeAgentException("多个子任务失败", List.of(new AgentExceptionInfo("one", "甲", temporary), new AgentExceptionInfo("two", "乙", denied)));
        assertTrue(ModelFailureMapper.map(combined).isEmpty());
        var transientOnly = new CompositeAgentException("临时服务不可用", List.of(new AgentExceptionInfo("one", "甲", temporary)));
        assertEquals("MODEL_TEMPORARY_FAILURE", ModelFailureMapper.map(transientOnly).orElseThrow().code());
    }

    @Test
    void frameworkTransportClassificationIsUsedWithoutLeakingTheRemoteBody() {
        var temporary = new HttpTransportException("不可展示的原始连接信息", new IOException("具体网络实现的连接中断"));
        var mapped = ModelFailureMapper.map(new RuntimeException(temporary)).orElseThrow();
        assertEquals("MODEL_TEMPORARY_FAILURE", mapped.code());
        assertFalse(mapped.getReason().contains("原始连接"));
        var denied = ModelFailureMapper.map(new HttpTransportException("原始认证信息", 403, "不能返回用户的正文")).orElseThrow();
        assertEquals("MODEL_ACCESS_DENIED", denied.code());
        assertFalse(denied.getReason().contains("正文"));
        assertTrue(ModelFailureMapper.map(new IllegalArgumentException("本地参数错误")).isEmpty());
    }
}
