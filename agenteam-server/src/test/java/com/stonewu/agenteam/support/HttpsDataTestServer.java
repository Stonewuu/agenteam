package com.stonewu.agenteam.support;

import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import okhttp3.OkHttpClient;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 测试专用证书只交给本次客户端，仍校验证书和主机名，不改变系统信任配置。
 */
public final class HttpsDataTestServer implements AutoCloseable {
    private final HttpsServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final OkHttpClient client;

    public HttpsDataTestServer() {
        try {
            var certificate = new HeldCertificate.Builder().commonName("数据接口测试")
                .addSubjectAlternativeName("source.example.test").addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").build();
            var serverCertificates = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
            var trusted = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
            client = new OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager()).build();
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverCertificates.sslContext()));
            server.setExecutor(executor);
            server.start();
        } catch (Exception failure) {
            executor.shutdownNow();
            throw new IllegalStateException("无法启动隔离的加密数据接口", failure);
        }
    }

    public String origin() {
        return "https://source.example.test:" + server.getAddress().getPort();
    }

    public void handle(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    public RestrictedHttpClient client() {
        return new RestrictedHttpClient(new OutboundAddressPolicy(origin(), ignored -> List.of(InetAddress.getByName("127.0.0.1"))), client);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
