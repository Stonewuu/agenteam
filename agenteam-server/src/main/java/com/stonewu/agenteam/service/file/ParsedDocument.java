package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.file.entity.CsvProfile;
import com.stonewu.agenteam.model.file.entity.CsvRow;
import com.stonewu.agenteam.model.file.entity.DocumentChunk;
import com.stonewu.agenteam.service.file.parser.DocumentParserMain;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 逐块消费解析输出，避免把整份知识资料加载到应用内存。
 */
public class ParsedDocument implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParsedDocument.class);
    private final Path directory;
    private final Runnable cleanupAction;
    private final ObjectMapper json;
    private final DocumentParserMain.Result result;

    ParsedDocument(Path directory, ObjectMapper json, DocumentParserMain.Result result) {
        this(directory, json, result, () -> cleanup(directory));
    }

    ParsedDocument(Path directory, ObjectMapper json, DocumentParserMain.Result result, Runnable cleanupAction) {
        this.directory = directory;
        this.json = json;
        this.result = result;
        this.cleanupAction = cleanupAction;
    }

    public int chunkCount() {
        return result.chunkCount();
    }

    public Integer pageCount() {
        return result.pageCount();
    }

    public CsvProfile csv() {
        return result.csv();
    }

    public void forEachCsv(Consumer<CsvRow> consumer) {
        if (result.csv() == null) {
            throw new IllegalStateException(Messages.text("error.file.parsedDocument.forEachCsv.1"));
        }
        try (var reader = Files.newBufferedReader(directory.resolve("rows.jsonl"))) {
            String line;
            long count = 0;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 20 * 1024 * 1024) {
                    throw new IOException(Messages.text("error.file.parsedDocument.forEachCsv.2"));
                }
                var row = json.readValue(line, CsvRow.class);
                if (row.row() != ++count || row.values().size() != result.csv().columns().size()) {
                    throw new IOException(Messages.text("error.file.parsedDocument.forEachCsv.3"));
                }
                consumer.accept(row);
            }
            if (count != result.csv().rowCount()) {
                throw new IOException(Messages.text("error.file.parsedDocument.forEachCsv.4"));
            }
        } catch (IOException invalid) {
            throw DocumentParserProcess.failure("CSV_FORMAT_INVALID");
        }
    }

    public void forEach(Consumer<DocumentChunk> consumer) {
        try (BufferedReader reader = Files.newBufferedReader(directory.resolve("chunks.jsonl"))) {
            int count = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 16000) {
                    throw new IOException(Messages.text("error.file.parsedDocument.forEach.1"));
                }
                DocumentChunk chunk = json.readValue(line, DocumentChunk.class);
                if (chunk.ordinal() != ++count || chunk.text().isBlank() || chunk.text()
                    .codePointCount(0, chunk.text().length()) > 1200
                    || chunk.overlapCharacters() < 0 || chunk.overlapCharacters() > 100
                    || chunk.overlapCharacters() >= chunk.text().codePointCount(0, chunk.text().length())) {
                    throw new IOException(Messages.text("error.file.parsedDocument.forEach.2"));
                }
                consumer.accept(chunk);
            }
            if (count != result.chunkCount()) {
                throw new IOException(Messages.text("error.file.parsedDocument.forEach.3"));
            }
        } catch (IOException invalid) {
            throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
        }
    }

    @Override
    public void close() {
        cleanupAction.run();
    }

    static void cleanup(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    WorkspaceStore.deleteTree(absolute.getParent(), absolute);
                    return;
                } catch (FileSystemException occupied) {
                    // Windows 在解析进程已退出时仍可能短暂报告目录占用，最多等待两秒。
                    if (!System.getProperty("os.name").startsWith("Windows") || attempt >= 40) {
                        throw occupied;
                    }
                    if (attempt == 0) {
                        LOGGER.warn(Messages.log("log.file.parsedDocument.cleanup.1"), absolute, occupied);
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException cancelled) {
                        interrupted = true;
                    }
                }
            }
        } catch (IOException unavailable) {
            throw FileStorageKeys.storageUnavailable(unavailable);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
