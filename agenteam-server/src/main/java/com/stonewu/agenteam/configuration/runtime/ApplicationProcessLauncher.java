package com.stonewu.agenteam.configuration.runtime;

import com.stonewu.agenteam.configuration.sandbox.SandboxServerMain;
import com.stonewu.agenteam.service.file.parser.DocumentParserMain;
import org.springframework.boot.SpringApplication;

import java.util.Arrays;

/** 保证两个发行版本均可启动业务服务、办公执行器和文档解析子进程。 */
public final class ApplicationProcessLauncher {
    private ApplicationProcessLauncher() {
    }

    public static void launch(Class<?> application, String[] args) {
        if (args.length > 0 && args[0].equals("--sandbox-server")) {
            SandboxServerMain.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("--document-parser")) {
            DocumentParserMain.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(application, args);
    }
}
