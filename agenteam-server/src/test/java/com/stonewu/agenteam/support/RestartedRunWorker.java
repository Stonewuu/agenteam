package com.stonewu.agenteam.support;

import com.stonewu.agenteam.AgenteamApplication;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.service.execution.ExecutionTaskFactory;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.execution.RunWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 共有业务的独立进程验证入口：沿用父测试显式安装的多企业组件，只领取已提交任务。
 */
public final class RestartedRunWorker {
    private RestartedRunWorker() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("spring.devtools.restart.enabled", "false");
        var values = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            values.load(input);
        }
        Map<String, Object> properties = new HashMap<>();
        values.forEach((key, value) -> properties.put(key.toString(), value));
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("restart-test", properties));
        var application = new SpringApplication(AgenteamApplication.class, SharedEnterpriseTestEdition.class);
        application.setEnvironment(environment);
        try (var context = application.run()) {
            var runs = context.getBean(RunMapper.class);
            var worker = new RunWorker(context.getBean(RunLifecycleService.class), context.getBean(ExecutionTaskFactory.class));
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
                while (System.nanoTime() < deadline) {
                    worker.poll();
                    var run = runs.find(args[1], args[2], false).orElseThrow();
                    if (run.terminal()) {
                        if (!run.status().equals("completed")) {
                            throw new IllegalStateException("新进程执行失败：" + run.errorCode());
                        }
                        return;
                    }
                    TimeUnit.MILLISECONDS.sleep(100);
                }
                throw new IllegalStateException("新进程未在规定时间内完成待领取任务");
            } finally {
                worker.close();
            }
        }
    }
}
