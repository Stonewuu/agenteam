package com.stonewu.agenteam.service.file.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.file.entity.CsvProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 独立解析入口不启动 Spring，不连接数据库、模型或业务网络。
 */
public class DocumentParserMain {
    public record Result(boolean success, String errorCode, int chunkCount, Integer pageCount, CsvProfile csv) {
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.exit(2);
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        long parent = Long.getLong("agenteam.parser.parent", 0);
        var watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("解析进程期限检查").factory());
        watchdog.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() >= deadline || (parent > 0 && ProcessHandle.of(parent)
                .map(handle -> !handle.isAlive()).orElse(true))) {
                Runtime.getRuntime().halt(3);
            }
        }, 1, 1, TimeUnit.SECONDS);
        Path source = Path.of(args[0]), directory = Path.of(args[2]);
        Result result;
        try {
            if (Files.size(source) > 20L * 1024 * 1024) {
                throw new DocumentParseFailure("FILE_TOO_LARGE");
            }
            if (args[1].equals("csv")) {
                result = new Result(true, null, 0, null,
                    new CsvFileParser().parse(source, directory.resolve("rows.jsonl")));
            } else {
                int count;
                Integer pages;
                try (var writer = new DocumentChunkWriter(directory.resolve("chunks.jsonl"))) {
                    pages = new DocumentTextExtractor().extract(source, args[1], writer);
                    writer.finishSection();
                    count = writer.count();
                }
                if (count == 0) {
                    throw new DocumentParseFailure("FILE_NO_TEXT");
                }
                result = new Result(true, null, count, pages, null);
            }
        } catch (DocumentParseFailure failure) {
            result = new Result(false, failure.code(), 0, null, null);
        } catch (OutOfMemoryError exhausted) {
            result = new Result(false, "FILE_EXPANDED_TOO_LARGE", 0, null, null);
        } catch (Exception invalid) {
            result = new Result(false, "FILE_TYPE_INVALID", 0, null, null);
        }
        try {
            new ObjectMapper().writeValue(directory.resolve("result.json").toFile(), result);
        } catch (Exception failed) {
            System.exit(2);
            return;
        }
        System.exit(result.success() ? 0 : 1);
    }
}
