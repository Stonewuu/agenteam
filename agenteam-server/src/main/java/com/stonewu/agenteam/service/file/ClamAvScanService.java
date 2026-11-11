package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 扫描开关关闭或未配置地址时跳过病毒扫描，文件处理队列继续运行。
 */
@Service
public class ClamAvScanService {
    private static final Logger LOG = LoggerFactory.getLogger(ClamAvScanService.class);

    public enum Result {CLEAN, SKIPPED, REJECTED, UNAVAILABLE}

    private final String host;
    private final int port;
    private final FileContentStorage storage;
    private final boolean enabled;

    public ClamAvScanService(@Value("${files.scan.enabled:false}") boolean enabled,
                             @Value("${files.scan.host:}") String host,
                             @Value("${files.scan.port:3310}") int port, FileContentStorage storage) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(Messages.text("error.file.clamAvScanService.init.1"));
        }
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.storage = storage;
        if (!enabled) {
            LOG.info(Messages.log("log.file.clamAvScanService.init.1"));
        } else if (host.isBlank()) {
            LOG.info(Messages.log("log.file.clamAvScanService.init.2"));
        }
    }

    public Result scan(FileRecord file) {
        if (!enabled || host.isBlank()) {
            return Result.SKIPPED;
        }
        var result = new CompletableFuture<Result>();
        try (var socket = new Socket()) {
            Thread scanner = Thread.ofVirtual().name(Messages.log("log.file.clamAvScanService.scan.1")).start(() -> {
                try {
                    socket.connect(new InetSocketAddress(host, port), 5000);
                    socket.setSoTimeout(15000);
                    var output = new DataOutputStream(socket.getOutputStream());
                    output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                    try (var input = storage.open(file)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        long sent = 0;
                        while ((length = input.read(buffer)) >= 0) {
                            if (length == 0) {
                                continue;
                            }
                            sent += length;
                            if (sent > 20L * 1024 * 1024 || Thread.currentThread().isInterrupted()) {
                                throw new IllegalStateException(Messages.text("error.file.clamAvScanService.scan.1"));
                            }
                            output.writeInt(length);
                            output.write(buffer, 0, length);
                        }
                        if (sent != file.sizeBytes()) {
                            throw new IllegalStateException(Messages.text("error.file.clamAvScanService.scan.2"));
                        }
                    }
                    output.writeInt(0);
                    output.flush();
                    var reply = new ByteArrayOutputStream();
                    int next;
                    while ((next = socket.getInputStream().read()) >= 0) {
                        if (reply.size() >= 4096) {
                            throw new IllegalStateException(Messages.text("error.file.clamAvScanService.scan.3"));
                        }
                        reply.write(next);
                    }
                    String text = reply.toString(StandardCharsets.UTF_8);
                    if (!text.endsWith("\0")) {
                        result.complete(Result.UNAVAILABLE);
                    } else {
                        boolean clean = false, rejected = false, unknown = false;
                        for (String record : text.split("\0")) {
                            if (record.equals("stream: OK")) {
                                clean = true;
                            } else if (record.startsWith("stream: ") && record.endsWith(" FOUND")) {
                                rejected = true;
                            } else {
                                unknown = true;
                            }
                        }
                        result.complete(
                            rejected ? Result.REJECTED : clean && !unknown ? Result.CLEAN : Result.UNAVAILABLE);
                    }
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                }
            });
            try {
                return result.get(20, TimeUnit.SECONDS);
            } catch (InterruptedException cancelled) {
                scanner.interrupt();
                Thread.currentThread().interrupt();
                LOG.warn(Messages.log("log.file.clamAvScanService.scan.2"), file.id(), cancelled);
                return Result.UNAVAILABLE;
            } catch (Exception unavailable) {
                scanner.interrupt();
                LOG.warn(Messages.log("log.file.clamAvScanService.scan.3"), file.id(), unavailable);
                return Result.UNAVAILABLE;
            }
        } catch (Exception unavailable) {
            LOG.warn(Messages.log("log.file.clamAvScanService.scan.4"), file.id(), unavailable);
            return Result.UNAVAILABLE;
        }
    }
}
