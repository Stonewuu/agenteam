package com.stonewu.agenteam.support;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * 从独立 Java 源码编译真实程序包，验证 PF4J 索引和公共接口的实际兼容性。
 */
public final class JavaPluginFixture {
    private JavaPluginFixture() {
    }

    public static byte[] build(String version) throws Exception {
        var directory = Files.createTempDirectory("plugin-test-compile-");
        try {
            var source = directory.resolve("Example.java");
            Files.writeString(source, """
                import com.stonewu.plugin.api.JavaToolPlugin;
                import com.stonewu.plugin.api.JavaPluginContext;
                import com.stonewu.tool.api.ToolSpec;
                import java.util.List;
                import java.util.Map;
                import java.io.IOException;
                public class Example implements JavaToolPlugin {
                    public List<ToolSpec> tools() {
                        return List.of(new ToolSpec("echo", "示例工具", "返回带前缀的内容", "",
                            Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string"))),
                            null, Map.of(), "read", false, false, true, List.of(), 30));
                    }
                    public Map<String, Object> invoke(String name, Map<String, Object> args, Map<String, Object> config, JavaPluginContext context) {
                        if (name.equals("fail")) {
                            var failure = new IllegalStateException("请求失败 " + config.get("prefix"),
                                new IOException("Connection reset " + args.get("value") + " " + context.credentialHeaders().get("Authorization")));
                            failure.addSuppressed(new IllegalStateException("关闭连接失败"));
                            throw failure;
                        }
                        if (name.equals("hang")) {
                            while (true) {
                                Thread.onSpinWait();
                            }
                        }
                        return Map.of("content", config.getOrDefault("prefix", "") + args.get("value").toString(),
                            "pid", ProcessHandle.current().pid());
                    }
                }
                """);
            int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path"), source.toString());
            if (result != 0) {
                throw new IllegalStateException("测试插件编译失败");
            }
            var manifest = new Manifest();
            var attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.putValue("Plugin-Id", "example");
            attributes.putValue("Plugin-Version", version);
            attributes.putValue("Plugin-Requires", "1.0.0");
            attributes.putValue("Plugin-Description", "测试 Java 插件");
            attributes.putValue("Plugin-Provider", "测试作者");
            var bytes = new ByteArrayOutputStream();
            try (var archive = new JarOutputStream(bytes, manifest)) {
                archive.putNextEntry(new JarEntry("Example.class"));
                archive.write(Files.readAllBytes(directory.resolve("Example.class")));
                archive.closeEntry();
                archive.putNextEntry(new JarEntry("META-INF/extensions.idx"));
                archive.write("Example\n".getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
                archive.putNextEntry(new JarEntry("META-INF/agenteam-plugin.json"));
                archive.write("""
                    {"name":"Java 示例","configurationSchema":{"type":"object","additionalProperties":false,
                    "properties":{"prefix":{"type":"string","title":"内容前缀"}}}}
                    """.getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
            return bytes.toByteArray();
        } finally {
            try (var files = Files.list(directory)) {
                for (var file : files.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(directory);
        }
    }
}
