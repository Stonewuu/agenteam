package com.stonewu.agenteam;

import com.stonewu.agenteam.configuration.runtime.AgenteamRuntimeConfiguration;
import com.stonewu.agenteam.configuration.runtime.ApplicationProcessLauncher;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(AgenteamRuntimeConfiguration.class)
public class AgenteamApplication {

    public static void main(String[] args) {
        ApplicationProcessLauncher.launch(AgenteamApplication.class, args);
    }
}
