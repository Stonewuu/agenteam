package com.stonewu.agenteam.service.data.mysql;

import com.stonewu.agenteam.service.i18n.Messages;

import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.util.ConfigurableSocketFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库连接只使用刚刚验证的地址，TLS（传输加密）校验仍使用原始主机名。
 */
public class PinnedMysqlSocketFactory extends ConfigurableSocketFactory {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name(Messages.log("log.data.pinnedMysqlSocketFactory.TIMER.1")).factory());
    private String requestedHost;

    @Override
    public void setConfiguration(Configuration configuration, String host) {
        requestedHost = host;
    }

    public static Scope scope(String host, int port, List<InetAddress> addresses, long deadline) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException(Messages.text("error.data.pinnedMysqlSocketFactory.scope.1"));
        }
        var scope = new Scope(host, port, addresses, deadline);
        CURRENT.set(scope);
        return scope;
    }

    @Override
    public Socket createSocket() throws IOException {
        Scope scope = CURRENT.get();
        if (scope == null || !scope.host.equalsIgnoreCase(requestedHost)) {
            throw new SocketException(Messages.text("error.data.pinnedMysqlSocketFactory.createSocket.1"));
        }
        IOException failure = null;
        for (InetAddress address : scope.addresses) {
            Socket socket = new Socket(Proxy.NO_PROXY);
            scope.sockets.add(socket);
            try {
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(scope.remaining());
                socket.connect(new InetSocketAddress(address, scope.port), scope.remaining());
                return socket;
            } catch (IOException failed) {
                failure = failed;
                scope.sockets.remove(socket);
                socket.close();
            }
        }
        if (failure != null) {
            throw failure;
        }
        throw new SocketException(Messages.text("error.data.pinnedMysqlSocketFactory.createSocket.2"));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        throw new SocketException(Messages.text("error.data.pinnedMysqlSocketFactory.createSocket.3"));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        throw new SocketException(Messages.text("error.data.pinnedMysqlSocketFactory.createSocket.3"));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        throw new SocketException(Messages.text("error.data.pinnedMysqlSocketFactory.createSocket.3"));
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
        throw new SocketException(Messages.text("error.data.pinnedMysqlSocketFactory.createSocket.3"));
    }

    public static final class Scope implements AutoCloseable {
        private final String host;
        private final int port;
        private final List<InetAddress> addresses;
        private final long deadline;
        private final Thread owner = Thread.currentThread();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ScheduledFuture<?> timeout;

        private Scope(String host, int port, List<InetAddress> addresses, long deadline) {
            this.host = host;
            this.port = port;
            this.addresses = List.copyOf(addresses);
            this.deadline = deadline;
            timeout = TIMER.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() >= deadline || owner.isInterrupted()) {
                    closeSockets();
                }
            }, 20, 20, TimeUnit.MILLISECONDS);
        }

        public void connected() {
            if (CURRENT.get() == this) {
                CURRENT.remove();
            }
        }

        private int remaining() throws SocketException {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0 || closed.get() || owner.isInterrupted()) {
                throw new SocketException(Messages.text("error.data.pinnedMysqlSocketFactory.remaining.1"));
            }
            return (int) Math.min(10000, remaining);
        }

        private void closeSockets() {
            closed.set(true);
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) { /* 连接已结束，无需再次读取。 */ }
            }
        }

        @Override
        public void close() {
            connected();
            timeout.cancel(false);
            closeSockets();
        }
    }
}
