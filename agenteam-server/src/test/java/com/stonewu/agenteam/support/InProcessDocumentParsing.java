package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.file.DocumentParserProcess;
import com.stonewu.agenteam.service.file.DocumentSandboxService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.file.parser.DocumentParserMain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * 业务接口测试仍解析原始文件和校验实际结果，避免每次上传都重启 Java。
 * 进程隔离、超时、取消及危险文档由 DocumentParserProcessTest 使用真实子进程验证。
 */
@TestConfiguration(proxyBeanMethods = false)
public class InProcessDocumentParsing {
    @Bean
    @Primary
    DocumentParserProcess businessDocumentParser(FileContentStorage storage, ObjectMapper json,
                                                @Value("${files.parser-root:${files.root:.agenteam/files}/.parsing}") String root,
                                                DocumentSandboxService sandbox,
                                                @Value("${files.parser.sandbox-enabled:true}") boolean sandboxEnabled) {
        return new DocumentParserProcess(storage, json, root, sandbox, sandboxEnabled) {
            @Override
            protected int executeParser(Path directory, Path source, String format, String purpose,
                                        BooleanSupplier current, long deadline) throws IOException {
                if (!current.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw failure("FILE_PROCESSING_CANCELLED");
                }
                var result = DocumentParserMain.parse(source, format, directory);
                json.writeValue(directory.resolve("result.json").toFile(), result);
                if (System.nanoTime() >= deadline) {
                    throw failure("FILE_PROCESSING_TIMEOUT");
                }
                return result.success() ? 0 : 1;
            }
        };
    }
}
