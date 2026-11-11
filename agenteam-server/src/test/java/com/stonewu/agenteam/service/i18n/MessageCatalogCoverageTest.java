package com.stonewu.agenteam.service.i18n;

import com.stonewu.agenteam.support.i18n.MessageCatalogAssertions;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;

class MessageCatalogCoverageTest {
    @Test
    void productionMessagesAndConstraintReferencesUseExistingCatalogEntries() throws Exception {
        MessageCatalogAssertions.verify(Path.of("src/main/java"), Path.of("agenteam-agentscope-compat/src/main/java"),
            Path.of("agenteam-tool-api/src/main/java"), Path.of("agenteam-plugin-api/src/main/java"));
    }
}
