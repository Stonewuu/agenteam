package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.AgenteamApplication;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.file.parser.DocumentChunkWriter;
import com.stonewu.agenteam.service.file.parser.DocumentParserMain;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 每份资料在独立 Java 进程解析，限制内存、时间和输出；业务凭据不会传给子进程。
 */
@Service
public class DocumentParserProcess {
    private final FileContentStorage storage;
    private final ObjectMapper json;
    private final Path root;
    private final DocumentSandboxService sandbox;
    private final boolean sandboxEnabled;

    public DocumentParserProcess(FileContentStorage storage, ObjectMapper json, String root) {
        this(storage, json, root, null, false);
    }

    @Autowired
    public DocumentParserProcess(FileContentStorage storage, ObjectMapper json,
                                 @Value("${files.parser-root:${files.root:.agenteam/files}/.parsing}") String root,
                                 DocumentSandboxService sandbox,
                                 @Value("${files.parser.sandbox-enabled:true}") boolean sandboxEnabled) {
        this.storage = storage;
        this.json = json;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.sandbox = sandbox;
        this.sandboxEnabled = sandboxEnabled;
    }

    public ParsedDocument parse(FileRecord file, BooleanSupplier current) {
        return parse(file, current, Duration.ofSeconds(120));
    }

    ParsedDocument parse(FileRecord file, BooleanSupplier current, Duration timeout) {
        if (sandboxEnabled && !file.purpose().equals("data_import")) {
            return sandbox.extract(file, current, timeout);
        }
        Path directory = null;
        boolean retained = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!Set.of("knowledge", "attachment", "data_import", "collaboration_artifact").contains(file.purpose())) {
                throw failure("FILE_TYPE_INVALID");
            }
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new IOException(Messages.text("error.file.documentParserProcess.parse.1"));
            }
            directory = Files.createTempDirectory(root, "parse-");
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            }
            Path source = directory.resolve("source.data");
            copySource(file, source, current, deadline);
            int exitCode = executeParser(directory, source, FileUploadPolicy.extension(file.originalName()),
                file.purpose(), current, deadline);
            if (!current.getAsBoolean()) {
                throw failure("FILE_PROCESSING_CANCELLED");
            }
            Path status = directory.resolve("result.json");
            if (!Files.isRegularFile(status) || Files.size(status) > 65536) {
                throw failure("FILE_TYPE_INVALID");
            }
            var result = json.readValue(status.toFile(), DocumentParserMain.Result.class);
            if (exitCode != 0 || !result.success()) {
                throw failure(result.errorCode());
            }
            if (file.purpose().equals("data_import")) {
                if (result.csv() == null || result.csv().rowCount() < 0 || result.csv().rowCount() > 100000
                    || result.csv().columns().isEmpty() || result.csv().columns().size() > 100) {
                    throw failure("FILE_TYPE_INVALID");
                }
            } else if (result.chunkCount() < 1 || result.chunkCount() > 200000) {
                throw failure("FILE_TYPE_INVALID");
            }
            retained = true;
            return new ParsedDocument(directory, json, result);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw failure("FILE_PROCESSING_CANCELLED");
        } catch (IOException unavailable) {
            throw FileStorageKeys.storageUnavailable(unavailable);
        } finally {
            if (directory != null && !retained) {
                ParsedDocument.cleanup(directory);
            }
        }
    }

    /**
     * 隔离进程执行与格式解析分开，业务接口测试可复用真实解析实现。
     */
    protected int executeParser(Path directory, Path source, String format, String purpose,
                                BooleanSupplier current, long deadline) throws IOException, InterruptedException {
        Process process = null;
        try {
            var command = new ProcessBuilder(command(directory, source, format)).directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            command.environment().keySet().removeIf(
                name -> !Set.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL")
                    .contains(name.toUpperCase(Locale.ROOT)));
            process = command.start();
            process.getOutputStream().close();
            while (process.isAlive()) {
                if (!current.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw failure("FILE_PROCESSING_CANCELLED");
                }
                if (System.nanoTime() >= deadline) {
                    throw failure("FILE_PROCESSING_TIMEOUT");
                }
                var output = directory.resolve(purpose.equals("data_import") ? "rows.jsonl" : "chunks.jsonl");
                if (Files.exists(output) && Files.size(output) > DocumentChunkWriter.MAX_EXPANDED_BYTES) {
                    throw failure("FILE_EXPANDED_TOO_LARGE");
                }
                process.waitFor(100, TimeUnit.MILLISECONDS);
            }
            return process.exitValue();
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void copySource(FileRecord file, Path destination, BooleanSupplier current,
                            long deadline) throws IOException {
        try (var input = storage.open(file); var output = Files.newOutputStream(destination)) {
            var digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (!current.getAsBoolean()) {
                    throw failure("FILE_PROCESSING_CANCELLED");
                }
                if (System.nanoTime() >= deadline) {
                    throw failure("FILE_PROCESSING_TIMEOUT");
                }
                size += length;
                if (size > FileUploadPolicy.maxBytes(file.purpose())) {
                    throw failure("FILE_TOO_LARGE");
                }
                digest.update(buffer, 0, length);
                output.write(buffer, 0, length);
            }
            if (size != file.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
                throw failure("FILE_CONTENT_MISMATCH");
            }
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(Messages.text("error.file.documentParserProcess.copySource.1"), missing);
        }
    }

    private List<String> command(Path directory, Path source, String format) throws IOException {
        var args = new ArrayList<String>(
            List.of("-Xmx256m", "-Xss512k", "-XX:MaxDirectMemorySize=64m", "-XX:MaxMetaspaceSize=128m",
                "-XX:ActiveProcessorCount=2", "-XX:-UsePerfData",
                "-Dagenteam.parser.parent=" + ProcessHandle.current().pid(),
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Duser.home=" + directory,
                "-Djava.io.tmpdir=" + directory));
        var application = new ApplicationHome(AgenteamApplication.class).getSource();
        if (application != null && application.isFile() && application.getName().endsWith(".jar")) {
            args.addAll(List.of("-jar", application.getAbsolutePath(), "--document-parser"));
        } else {
            String classpath = String.join(File.pathSeparator, Arrays.stream(
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                        .split(Pattern.quote(File.pathSeparator), -1))
                .map(value -> value.endsWith("*") ? Path.of(value.substring(0, value.length() - 1))
                    .toAbsolutePath() + File.separator + "*" : Path.of(value).toAbsolutePath().toString()).toList());
            args.addAll(List.of("-cp", classpath, DocumentParserMain.class.getName()));
        }
        args.addAll(List.of(source.toString(), format, directory.toString()));
        Path argumentFile = directory.resolve("parser.args");
        Files.writeString(argumentFile, String.join("\n",
            args.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()));
        return List.of(Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), "@" + argumentFile);
    }

    public int cleanAbandoned(Instant before, int limit) {
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            return 0;
        }
        try (var paths = Files.find(root, 1,
            (path, attributes) -> attributes.isDirectory() && !attributes.isSymbolicLink()
                && path.getFileName().toString().matches("parse-[0-9]+") && attributes.lastModifiedTime().toInstant()
                .isBefore(before))) {
            var expired = paths.limit(limit).toList();
            for (var path : expired) {
                ParsedDocument.cleanup(path);
            }
            return expired.size();
        } catch (IOException unavailable) {
            throw FileStorageKeys.storageUnavailable(unavailable);
        }
    }

    public static ApiException failure(String code) {
        String actual = code == null ? "FILE_TYPE_INVALID" : code;
        String message = switch (actual) {
            case "FILE_NO_TEXT" -> Messages.text("error.file.documentParserProcess.failure.1");
            case "FILE_ENCRYPTED" -> Messages.text("error.file.documentParserProcess.failure.2");
            case "FILE_ACTIVE_CONTENT" -> Messages.text("error.file.documentParserProcess.failure.3");
            case "FILE_EXPANDED_TOO_LARGE" -> Messages.text("error.file.documentParserProcess.failure.4");
            case "FILE_PROCESSING_TIMEOUT" -> Messages.text("error.file.documentParserProcess.failure.5");
            case "FILE_PROCESSING_CANCELLED" -> Messages.text("error.file.documentParserProcess.failure.6");
            case "FILE_CONTENT_MISMATCH" -> Messages.text("error.file.documentParserProcess.failure.7");
            case "CSV_COLUMN_LIMIT" -> Messages.text("error.file.documentParserProcess.failure.8");
            case "CSV_ROW_LIMIT" -> Messages.text("error.file.documentParserProcess.failure.9");
            case "CSV_HEADER_INVALID" -> Messages.text("error.file.documentParserProcess.failure.10");
            case "CSV_COLUMN_COUNT_MISMATCH" -> Messages.text("error.file.documentParserProcess.failure.11");
            case "CSV_FORMAT_INVALID" -> Messages.text("error.file.documentParserProcess.failure.12");
            default -> Messages.text("error.file.documentParserProcess.failure.13");
        };
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, actual, message);
    }
}
