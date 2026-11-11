package com.stonewu.agenteam.configuration.modelprofile;

import com.stonewu.agenteam.service.i18n.Messages;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 开发库可能直接记录完整响应或解析异常。保留组件、异常位置与原因类别，具体请求由平台记录脱敏诊断。
 */
@Component
public final class ModelLibraryLogging extends TurboFilter implements InitializingBean, DisposableBean {
    private static final Logger LOG = (Logger) LoggerFactory.getLogger(ModelLibraryLogging.class);
    private LoggerContext context;

    @Override
    public void afterPropertiesSet() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext logback) {
            context = logback;
            setContext(logback);
            start();
            logback.addTurboFilter(this);
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] arguments, Throwable failure) {
        String name = logger.getName();
        if (!name.startsWith("io.agentscope.extensions.model.") && !name.startsWith("com.openai.")
            && !name.startsWith("com.anthropic.") && !name.startsWith("com.google.genai.")
            && !name.equals("io.agentscope.core.model.transport.OkHttpTransport")) {
            return FilterReply.NEUTRAL;
        }
        if (level.isGreaterOrEqual(Level.WARN)) {
            if (failure == null && arguments != null && arguments.length > 0
                && arguments[arguments.length - 1] instanceof Throwable cause) {
                failure = cause;
            }
            // 不传递开发库参数或拼接后的消息，防止异常格式及工具名称回显正文。
            LOG.warn(Messages.log("log.modelprofile.modelLibraryLogging.decide.1"), name, category(format),
                stack(failure, new IdentityHashMap<>()));
        }
        return FilterReply.DENY;
    }

    private String category(String format) {
        String value = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (value.contains("parse") || value.contains("json") || value.contains("serialize")) {
            return Messages.log("log.modelprofile.modelLibraryLogging.category.1");
        }
        if (value.contains("missing") || value.contains("empty")) {
            return Messages.log("log.modelprofile.modelLibraryLogging.category.2");
        }
        if (value.contains("http") || value.contains("request") || value.contains("connect")) {
            return Messages.log("log.modelprofile.modelLibraryLogging.category.3");
        }
        return Messages.log("log.modelprofile.modelLibraryLogging.category.4");
    }

    private Throwable stack(Throwable original, Map<Throwable, Throwable> visited) {
        if (original == null || visited.containsKey(original)) {
            return null;
        }
        var safe = new IllegalStateException(Messages.log("error.modelprofile.modelLibraryLogging.stack.1", original.getClass().getName()));
        visited.put(original, safe);
        safe.setStackTrace(original.getStackTrace());
        var cause = stack(original.getCause(), visited);
        if (cause != null) {
            safe.initCause(cause);
        }
        for (var suppressed : original.getSuppressed()) {
            var item = stack(suppressed, visited);
            if (item != null) {
                safe.addSuppressed(item);
            }
        }
        return safe;
    }

    @Override
    public void destroy() {
        stop();
        if (context != null) {
            context.getTurboFilterList().remove(this);
        }
    }
}
