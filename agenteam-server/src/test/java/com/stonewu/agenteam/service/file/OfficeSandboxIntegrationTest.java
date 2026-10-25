package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.support.TestOutputPaths;
import com.stonewu.agenteam.support.ReusableOfficeSandbox;
import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共用真实办公容器验证三类文档、公式重算、中文渲染和附件解析。
 * 生产沙箱的独立执行和导出方式由 DockerWorkspaceRunnerTest 验证。
 */
@Tag("external-process")
class OfficeSandboxIntegrationTest {
    private final Path directory = TestOutputPaths.path("office-integration", UUID.randomUUID().toString());
    private ReusableOfficeSandbox runner;

    @AfterEach
    void closeOfficeContainer() {
        if (runner != null) {
            runner.close();
        }
    }

    @Test
    void createsReadsRecalculatesRendersAndParsesFilesUsingOneContainer() throws Exception {
        var settings = new WorkspaceSettings(directory.toString(), "agenteam/sandbox-office:local", true, 128, 20, 2000, 512, 1, 128, 180, "none");
        runner = new ReusableOfficeSandbox(settings.image());
        Path source = directory.resolve("source");
        for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
            Files.createDirectories(source.resolve(area));
        }
        for (String name : List.of("create_documents.py", "create_slides.cjs", "check_documents.py")) {
            try (var input = getClass().getResourceAsStream("/office/" + name)) {
                Files.copy(input, source.resolve("work/" + name));
            }
        }
        Path first = directory.resolve("first");
        try (var control = new ToolCallControl(() -> {
        })) {
            var created = runner.execute(source, first, directory, "office-create", "python create_documents.py && node create_slides.cjs && python check_documents.py", "work", Duration.ofSeconds(120), control);
            assertEquals(0, created.exitCode(), Files.readString(first.resolve(created.stderrPath())));
        }
        Files.createDirectories(first.resolve("inputs"));
        Files.createDirectories(first.resolve("tool-results"));
        String containerId = runner.containerId();
        assertNotNull(containerId);
        Path second = directory.resolve("second");
        String command = "agenteam-office recalculate /workspace/outputs/sample.xlsx --output /workspace/outputs/calculated.xlsx\n"
            + "agenteam-office render /workspace/outputs/sample.docx --output-dir /workspace/outputs/preview/docx\n"
            + "agenteam-office render /workspace/outputs/sample.xlsx --output-dir /workspace/outputs/preview/xlsx\n"
            + "agenteam-office render /workspace/outputs/sample.pptx --output-dir /workspace/outputs/preview/pptx\n"
            + "for kind in docx xlsx pptx; do pdftoppm -f 1 -singlefile -scale-to 1200 -png /workspace/outputs/preview/$kind/sample.pdf /workspace/outputs/preview/$kind/page; done\n"
            + "python -c \"from openpyxl import load_workbook; assert load_workbook('/workspace/outputs/calculated.xlsx',data_only=True)['计算']['B4'].value == 5\"";
        try (var control = new ToolCallControl(() -> {
        })) {
            var converted = runner.execute(first, second, directory, "office-convert", "set -e\n" + command, "work", Duration.ofSeconds(180), control);
            assertEquals(0, converted.exitCode(), Files.readString(second.resolve(converted.stderrPath())));
        }
        assertEquals(-1, Files.mismatch(first.resolve("outputs/sample.xlsx"), second.resolve("outputs/sample.xlsx")));
        Path pdf = second.resolve("outputs/preview/pptx/sample.pdf");
        try (var document = Loader.loadPDF(pdf.toFile())) {
            assertTrue(document.getNumberOfPages() >= 1);
            assertTrue(new PDFTextStripper().getText(document).contains("中文演示文稿"));
        }
        var storage = new FileContentStorage(directory.resolve("temporary").toString(), new LocalFileObjectStore(directory.resolve("files").toString()));
        var parser = new DocumentSandboxService(runner, settings, storage, new ObjectMapper(), new DockerWorkspaceCommands(), directory.resolve("documents").toString());
        for (String format : List.of("docx", "xlsx", "pptx")) {
            byte[] bytes = Files.readAllBytes(second.resolve("outputs/sample." + format));
            var saved = storage.write("office-test", new ByteArrayInputStream(bytes), 20L * 1024 * 1024);
            var file = new FileRecord(UUID.randomUUID().toString(), "office-test", "test-user", null, null, null, "attachment", "sample." + format,
                FileUploadPolicy.mediaType("attachment", "sample." + format), saved.size(), saved.sha256(), null, null, null,
                saved.size(), saved.sha256(), saved.key(), "ready", null, null, null, Instant.now());
            StringBuilder text = new StringBuilder();
            try (var parsed = parser.extract(file, () -> true, Duration.ofSeconds(60))) {
                parsed.forEach(chunk -> text.append(chunk.text()));
            }
            assertTrue(text.length() > 20, format);
            if (format.equals("xlsx")) {
                assertTrue(text.toString().contains("SUM(B2:B3)"));
            }
        }
        try (var paths = Files.list(directory.resolve("documents"))) {
            assertEquals(0, paths.count());
        }
        var invalid = new ByteArrayInputStream("不是办公文件".getBytes());
        assertThrows(ApiException.class, () -> parser.render(invalid, "docx", directory.resolve("invalid.pdf"), () -> true));
        Path empty = Files.createDirectory(directory.resolve("empty"));
        try (var control = new ToolCallControl(() -> { })) {
            var fresh = runner.execute(empty, directory.resolve("fresh"), directory, "fresh-workspace",
                "test ! -e /workspace/outputs/sample.docx && test ! -e /workspace/inputs/source.docx", ".",
                Duration.ofSeconds(15), control);
            assertEquals(0, fresh.exitCode(), "重新准备工作目录后不能保留上次调用的文档");
        }
        assertEquals(containerId, runner.containerId(), "文档创建、转换和解析应共用同一个测试容器");
        Path evidence = TestOutputPaths.path("office-runtime-evidence").toAbsolutePath();
        Files.createDirectories(evidence);
        for (String kind : List.of("docx", "xlsx", "pptx")) {
            Files.copy(second.resolve("outputs/preview/" + kind + "/sample.pdf"), evidence.resolve(kind + ".pdf"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(second.resolve("outputs/preview/" + kind + "/page.png"), evidence.resolve(kind + ".png"), StandardCopyOption.REPLACE_EXISTING);
        }
        for (String name : List.of("sample.docx", "sample.xlsx", "sample.pptx", "calculated.xlsx")) {
            Files.copy(second.resolve("outputs/" + name), evidence.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
