package com.stonewu.agenteam.service.modelprofile.protocol;

import com.sun.net.httpserver.HttpServer;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 请求登记阶段的取消必须阻止随后发出网络请求。 */
class NativeModelHttpClientTest {
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
