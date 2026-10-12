package com.stonewu.agenteam.service.plugin.javaworker;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * 开发与测试使用实际类路径，生产从同一可执行包选择独立入口。
 */
public final class JavaPluginCommand {
    private JavaPluginCommand() {
    }

    public static List<String> command(Path plugin) {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx256m");
        command.add("-XX:MaxMetaspaceSize=192m");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String boot = null;
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.endsWith(".jar")) {
                try (var archive = new JarFile(entry)) {
                    if (archive.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") != null) {
                        boot = Path.of(entry).toAbsolutePath().toString();
                        break;
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException("无法检查 Java 插件执行类路径", failure);
                }
            }
        }
        if (boot != null) {
            command.add("-Dloader.main=" + JavaPluginWorker.class.getName());
            command.addAll(List.of("-cp", boot, "org.springframework.boot.loader.launch.PropertiesLauncher"));
        } else {
            var paths = new ArrayList<String>();
            for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
                paths.add(Path.of(entry).toAbsolutePath().toString());
            }
            command.addAll(List.of("-cp", String.join(File.pathSeparator, paths), JavaPluginWorker.class.getName()));
        }
        command.add(plugin.toAbsolutePath().toString());
        return List.copyOf(command);
    }
}
