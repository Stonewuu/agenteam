package com.stonewu.agenteam.support;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 仅用于验证 ClamAV 字节协议和不同扫描回复，不用于产品文件检查。
 */
public final class FileScanTestServer implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public volatile String response = "stream: OK\0";
    public final List<byte[]> received = new CopyOnWriteArrayList<>();

    public FileScanTestServer() {
        try {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        executor.submit(() -> scan(socket));
                    } catch (IOException stopped) {
                        if (!server.isClosed()) {
                            throw new IllegalStateException("测试检查服务连接失败");
                        }
                    }
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动测试文件检查服务", failure);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    public void reset() {
        received.clear();
        response = "stream: OK\0";
    }

    private void scan(Socket socket) {
        try (socket) {
            var input = new DataInputStream(socket.getInputStream());
            if (!new String(input.readNBytes(10), StandardCharsets.US_ASCII).equals("zINSTREAM\0")) {
                throw new IOException("错误的内容检查命令");
            }
            var body = new ByteArrayOutputStream();
            int length;
            while ((length = input.readInt()) != 0) {
                if (length < 1 || length > 8192 || body.size() + length > 20 * 1024 * 1024) {
                    throw new IOException("文件检查分段长度不正确");
                }
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) {
                    throw new IOException("文件检查正文不完整");
                }
                body.write(bytes);
            }
            received.add(body.toByteArray());
            socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (IOException stopped) { /* 测试关闭连接或客户端取消时没有产品日志。 */ }
    }

    @Override
    public void close() throws IOException {
        server.close();
        executor.shutdownNow();
    }
}
