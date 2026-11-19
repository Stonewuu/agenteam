package com.stonewu.agenteam.service.modelprofile.protocol;

import com.sun.net.httpserver.HttpServer;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 请求登记阶段的取消必须阻止随后发出网络请求。 */
class NativeModelHttpClientTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.8", "198.18.0.110", "198.19.0.8", "169.254.1.2"})
    void configuredModelHostsAcceptPrivateAndProxyAddresses(String address) throws Exception {
        var expected = InetAddress.getByName(address);
        var resolver = new OutboundAddressPolicy("", ignored -> List.of(expected));
        try (var session = new ModelHttpSession("https://model.internal.test", resolver, () -> {
        })) {
            assertEquals(List.of(expected), session.client().dns().lookup("model.internal.test"));
        }
    }

    @Test
    void configuredHttpModelUsesTheResolvedInternalService() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        String address = "http://model.internal.test:" + server.getAddress().getPort();
        var resolver = new OutboundAddressPolicy("", ignored -> List.of(InetAddress.getLoopbackAddress()));
        try (var session = new ModelHttpSession(address, resolver, () -> {
        })) {
            try (var response = session.execute(new Request.Builder().url(address).build())) {
                assertEquals(204, response.code());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellationDuringRegistrationPreventsTheLaterNetworkRequest() throws Exception {
        var sends = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            sends.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        String address = "http://127.0.0.1:" + server.getAddress().getPort();
        var holder = new AtomicReference<ModelHttpSession>();
        try {
            try (var session = new ModelHttpSession(address, new OutboundAddressPolicy(""), () -> holder.get().cancel())) {
                holder.set(session);
                assertThrows(IOException.class, () -> session.execute(new Request.Builder().url(address).build()));
                assertEquals(0, sends.get());
            }
        } finally {
            server.stop(0);
        }
    }
}
