package com.stonewu.agenteam.service.plugin.javaworker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.plugin.api.JavaPluginContext;
import com.stonewu.plugin.api.JavaToolPlugin;
import com.stonewu.tool.api.ToolCancellation;
import org.pf4j.DefaultPluginManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仅由主服务启动的独立进程入口，不启动 Spring，也不在主进程加载插件类。
 */
public final class JavaPluginWorker {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JavaPluginWorker() {
    }

    public static void main(String[] arguments) throws Exception {
        InputStream input = System.in;
        OutputStream output = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        var protocol = new JavaPluginProtocol();
        JsonNode request = null;
        try {
            request = protocol.read(input);
            var manager = new DefaultPluginManager(Path.of(arguments[0]).getParent());
            manager.setSystemVersion(JavaToolPlugin.API_VERSION);
            var id = manager.loadPlugin(Path.of(arguments[0]));
            manager.startPlugin(id);
            var extensions = manager.getExtensions(JavaToolPlugin.class, id);
            if (extensions.size() != 1) {
                throw new IllegalArgumentException("程序包必须提供一个 Java 工具扩展");
            }
            var plugin = extensions.getFirst();
            Object result;
            if (request.path("mode").asText().equals("discover")) {
                result = plugin.tools();
            } else {
                try (var context = new Context(request, protocol, input, output)) {
                    context.beforeSend();
                    result = plugin.invoke(request.path("tool").asText(), object(request.path("arguments")),
                        object(request.path("configuration")), context);
                }
            }
            protocol.write(output, Map.of("kind", "result", "value", result));
            manager.stopPlugin(id);
            manager.unloadPlugin(id);
        } catch (Throwable failure) {
            protocol.write(output, Map.of("kind", "failure", "error",
                JavaPluginFailures.encode(JavaPluginFailures.safe(failure, request))));
        }
        System.exit(0);
    }

    private static Map<String, Object> object(JsonNode value) {
        return JSON.convertValue(value, new TypeReference<>() {
        });
    }

    private static final class Context implements JavaPluginContext, ToolCancellation, AutoCloseable {
        private final JsonNode request;
        private final JavaPluginProtocol protocol;
        private final InputStream input;
        private final OutputStream output;
        private final long deadline;
        private final List<AutoCloseable> resources = new ArrayList<>();

        private Context(JsonNode request, JavaPluginProtocol protocol, InputStream input, OutputStream output) {
            this.request = request;
            this.protocol = protocol;
            this.input = input;
            this.output = output;
            deadline = System.nanoTime() + timeout().toNanos();
        }

        @Override
        public String enterpriseId() {
            return request.path("enterpriseId").asText(null);
        }

        @Override
        public String actorId() {
            return request.path("actorId").asText(null);
        }

        @Override
        public String runId() {
            return request.path("runId").asText(null);
        }

        @Override
        public String operationId() {
            return request.path("operationId").asText(null);
        }

        @Override
        public Duration timeout() {
            return Duration.ofMillis(request.path("timeoutMillis").asLong(30000));
        }

        @Override
        public ToolCancellation cancellation() {
            return this;
        }

        @Override
        public Map<String, String> credentialHeaders() {
            return JSON.convertValue(request.path("credentialHeaders"), new TypeReference<>() {
            });
        }

        @Override
        public synchronized void beforeSend() {
            requireActive();
            try {
                protocol.write(output, Map.of("kind", "beforeSend"));
                if (!protocol.read(input).path("approved").asBoolean()) {
                    throw new IllegalStateException("插件调用已取消");
                }
            } catch (Exception failure) {
                throw new IllegalStateException("插件调用授权已失效", failure);
            }
        }

        @Override
        public void requireActive() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                throw new IllegalStateException("插件调用已取消或超时");
            }
        }

        @Override
        public synchronized <T extends AutoCloseable> T track(T resource) {
            resources.add(resource);
            return resource;
        }

        @Override
        public void close() throws Exception {
            Exception first = null;
            for (var resource : resources.reversed()) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    if (first == null) {
                        first = failure;
                    } else {
                        first.addSuppressed(failure);
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }
}
