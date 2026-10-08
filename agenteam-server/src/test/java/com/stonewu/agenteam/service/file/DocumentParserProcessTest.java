package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.file.entity.CsvRow;
import com.stonewu.agenteam.model.file.entity.DocumentChunk;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真正启动独立进程，验证中文、页码、恶意压缩内容及取消后的文件清理。
 */
class DocumentParserProcessTest {
    @TempDir
    Path root;

    private FileContentStorage storage() {
        return new FileContentStorage(root.resolve("temporary").toString(), new LocalFileObjectStore(root.resolve("objects").toString()));
    }

    private DocumentParserProcess parser() {
        return new DocumentParserProcess(storage(), new ObjectMapper(), root.resolve("parser").toString());
    }

    private FileRecord file(String name, byte[] bytes) {
        return file(name, bytes, "knowledge");
    }

    private FileRecord file(String name, byte[] bytes, String purpose) {
        var saved = storage().write("test-enterprise", new ByteArrayInputStream(bytes), 20L * 1024 * 1024);
        return new FileRecord(UUID.randomUUID().toString(), "test-enterprise", "test-user", null, null, null, purpose, name,
            FileUploadPolicy.mediaType(purpose, name), (long) bytes.length, saved.sha256(), null, null, null,
            saved.size(), saved.sha256(), saved.key(), "ready", null, null, null, Instant.now());
    }

    private List<DocumentChunk> parse(String name, byte[] bytes) {
        var chunks = new ArrayList<DocumentChunk>();
        try (var parsed = parser().parse(file(name, bytes), () -> true)) {
            parsed.forEach(chunks::add);
            assertEquals(parsed.chunkCount(), chunks.size());
        }
        return chunks;
    }

    private void rejects(String expected, String name, byte[] content) {
        assertEquals(expected, assertThrows(ApiException.class, () -> parser().parse(file(name, content), () -> true)).code());
    }

    @Test
    void chineseAndSupplementaryCharactersKeepTheirContentAndBoundedChunks() throws Exception {
        String text = "知识检索保留真实资料😀".repeat(350);
        var chunks = parse("资料.md", ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8));
        assertTrue(chunks.size() > 3);
        StringBuilder restored = new StringBuilder();
        for (var chunk : chunks) {
            assertTrue(chunk.text().codePointCount(0, chunk.text().length()) <= 1200);
            assertFalse(chunk.text().contains("\uFFFD"));
            restored.append(chunk.ordinal() == 1 ? chunk.text() : chunk.text().substring(chunk.text().offsetByCodePoints(0, 100)));
        }
        assertEquals(text, restored.toString());
        try (var remaining = Files.list(root.resolve("parser"))) {
            assertEquals(0, remaining.count());
        }
        rejects("FILE_TYPE_INVALID", "伪装.txt", new byte[]{'M', 'Z', 0, 5});
        rejects("FILE_TYPE_INVALID", "错误编码.txt", new byte[]{(byte) 0xff, (byte) 0xff});
        rejects("FILE_NO_TEXT", "空白.txt", "  \n ".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pdfPagesAreRealAndScannedEncryptedOrDamagedFilesAreRejected() throws Exception {
        var chunks = parse("pages.pdf", pdf(true, false));
        assertEquals(List.of(1, 2), chunks.stream().map(DocumentChunk::page).toList());
        assertTrue(chunks.get(1).text().contains("Second source page"));
        rejects("FILE_NO_TEXT", "scanned.pdf", pdf(false, false));
        rejects("FILE_ENCRYPTED", "encrypted.pdf", pdf(true, true));
        rejects("FILE_TYPE_INVALID", "broken.pdf", "%PDF-1.7\n缺少正文与交叉引用".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void docxReadsParagraphsAndRejectsMacrosExternalEntitiesAndExpandedBombs() throws Exception {
        String body = "<w:p><w:r><w:t>中文资料第一段</w:t></w:r></w:p><w:p><w:r><w:t>第二段引用内容</w:t></w:r></w:p>";
        var chunks = parse("资料.docx", docx(body, Map.of()));
        assertTrue(chunks.getFirst().text().contains("中文资料第一段\n第二段引用内容"));
        rejects("FILE_ACTIVE_CONTENT", "macro.docx", docx(body, Map.of("word/vbaProject.bin", "宏内容".getBytes(StandardCharsets.UTF_8))));
        rejects("FILE_TYPE_INVALID", "escape.docx", docx(body, Map.of("../outside.txt", new byte[]{1})));
        var entries = docxEntries(body);
        entries.put("word/document.xml", ("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///not-allowed'>]><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>&leak;</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        rejects("FILE_TYPE_INVALID", "entity.docx", zip(entries, false));
        rejects("FILE_EXPANDED_TOO_LARGE", "bomb.docx", zip(docxEntries(body), true));
    }

    @Test
    void deadlineAndLeaseLossStopTheProcessAndRemoveItsTemporaryFiles() throws Exception {
        var input = file("资料.txt", "正常文字".repeat(1000).getBytes(StandardCharsets.UTF_8));
        assertEquals("FILE_PROCESSING_TIMEOUT", assertThrows(ApiException.class, () -> parser().parse(input, () -> true, Duration.ofNanos(1))).code());
        var checks = new AtomicInteger();
        var cancelled = assertThrows(ApiException.class, () -> parser().parse(input, () -> checks.incrementAndGet() < 6));
        assertEquals("FILE_PROCESSING_CANCELLED", cancelled.code(), () -> Arrays.toString(cancelled.getSuppressed()));
        try (var remaining = Files.list(root.resolve("parser"))) {
            assertEquals(0, remaining.count());
        }
    }

    @Test
    void csvKeepsEmptyStringsDistinctFromNullAndInfersTypesFromEveryRow() {
        var source = file("数据.csv", "\uFEFF编号,备注,金额,日期,启用\r\n1,,1.25,2026-09-15,true\r\n2,\"\",2.50,2026-09-16,false\r\n".getBytes(StandardCharsets.UTF_8), "data_import");
        try (var parsed = parser().parse(source, () -> true)) {
            assertEquals(2, parsed.csv().rowCount());
            assertEquals(List.of("integer", "string", "decimal", "date", "boolean"), parsed.csv().columns().stream().map(column -> column.valueType()).toList());
            var rows = new ArrayList<CsvRow>();
            parsed.forEachCsv(rows::add);
            assertEquals(null, rows.get(0).values().get(1));
            assertEquals("", rows.get(1).values().get(1));
            assertTrue(parsed.csv().columns().get(1).nullable());
        }
        var mixed = file("混合.csv", "值\n1\n文本\n".getBytes(StandardCharsets.UTF_8), "data_import");
        try (var parsed = parser().parse(mixed, () -> true)) {
            assertEquals("string", parsed.csv().columns().getFirst().valueType());
        }
        var formula = file("公式.csv", "内容\n=1+1\n".getBytes(StandardCharsets.UTF_8), "data_import");
        try (var parsed = parser().parse(formula, () -> true)) {
            var rows = new ArrayList<CsvRow>();
            parsed.forEachCsv(rows::add);
            assertEquals("=1+1", rows.getFirst().values().getFirst());
        }
    }

    @Test
    void malformedCsvDuplicateHeadersAndExcessiveColumnsAreRejected() {
        for (String source : List.of("名称,名称\n一,二\n", "名称,年龄\n\"没有结束,12\n", "名称,年龄\n只有一列\n")) {
            var error = assertThrows(ApiException.class, () -> parser().parse(file("损坏.csv", source.getBytes(StandardCharsets.UTF_8), "data_import"), () -> true));
            assertTrue(List.of("CSV_FORMAT_INVALID", "CSV_COLUMN_COUNT_MISMATCH").contains(error.code()));
        }
        String headers = String.join(",", IntStream.range(0, 101).mapToObj(i -> "field" + i).toList());
        assertEquals("CSV_COLUMN_LIMIT", assertThrows(ApiException.class, () -> parser().parse(file("过多列.csv", headers.getBytes(StandardCharsets.UTF_8), "data_import"), () -> true)).code());
    }

    private byte[] pdf(boolean text, boolean encrypted) throws Exception {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 1; i <= 2; i++) {
                var page = new PDPage();
                doc.addPage(page);
                if (text) {
                    try (var content = new PDPageContentStream(doc, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(50, 600);
                        content.showText(i == 1 ? "First source page" : "Second source page");
                        content.endText();
                    }
                }
            }
            if (encrypted) {
                doc.protect(new StandardProtectionPolicy("owner-test-password", "reader-test-password", new AccessPermission()));
            }
            doc.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docx(String body, Map<String, byte[]> extra) throws Exception {
        var entries = docxEntries(body);
        entries.putAll(extra);
        return zip(entries, false);
    }

    private Map<String, byte[]> docxEntries(String body) {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml", ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private byte[] zip(Map<String, byte[]> entries, boolean bomb) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            if (bomb) {
                zip.putNextEntry(new ZipEntry("word/media/large.dat"));
                byte[] block = new byte[1024 * 1024];
                Arrays.fill(block, (byte) 'A');
                for (int i = 0; i < 101; i++) {
                    zip.write(block);
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
