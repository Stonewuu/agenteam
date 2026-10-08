package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 未配置病毒扫描时不读取或发送文件，也不声称文件已通过病毒扫描。
 */
class ClamAvScanServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void missingScannerSkipsWithoutReadingFileContent(String host) {
        var storage = mock(FileContentStorage.class);
        var file = mock(FileRecord.class);
        var scanner = new ClamAvScanService(true, host, 3310, storage);

        assertEquals(ClamAvScanService.Result.SKIPPED, scanner.scan(file));
        verifyNoInteractions(storage, file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "127.0.0.1", "scanner.internal"})
    void disabledScannerDoesNotReadOrSendFilesEvenWhenHostIsConfigured(String host) {
        var storage = mock(FileContentStorage.class);
        var file = mock(FileRecord.class);
        var scanner = new ClamAvScanService(false, host, 3310, storage);

        assertEquals(ClamAvScanService.Result.SKIPPED, scanner.scan(file));
        verifyNoInteractions(storage, file);
    }
}
