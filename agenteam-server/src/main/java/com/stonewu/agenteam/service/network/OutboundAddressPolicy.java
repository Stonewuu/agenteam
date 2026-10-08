package com.stonewu.agenteam.service.network;

import com.stonewu.agenteam.service.http.ApiException;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 每次请求先核对实际地址，随后由调用方将这些地址固定到本次连接。
 */
@Component
public class OutboundAddressPolicy {
    private final Set<String> privateOrigins;
    private final Dns resolver;
    private final Semaphore pendingLookups = new Semaphore(32);

    @Autowired
    public OutboundAddressPolicy(@Value("${network.allowed-private-origins:}") String allowed) {
        this(allowed, Dns.SYSTEM);
    }

    public OutboundAddressPolicy(String allowed, Dns resolver) {
        this.resolver = resolver;
        privateOrigins = Arrays.stream(allowed.split(",")).map(String::trim).filter(value -> !value.isEmpty())
            .map(value -> {
                HttpUrl url = parse(value);
                if (!url.encodedPath().equals("/") || url.query() != null) {
                    throw new IllegalStateException("网络允许列表必须填写完整协议、主机和端口，不能包含路径或查询参数");
                }
                return origin(url);
            }).collect(Collectors.toUnmodifiableSet());
    }

    public HttpUrl validateUrl(String value) {
        HttpUrl url = parse(value);
        if (!url.isHttps() && !privateOrigins.contains(origin(url))) {
            throw denied();
        }
        return url;
    }

    public List<InetAddress> resolve(HttpUrl url) {
        return resolve(url, Duration.ofSeconds(10));
    }

    public List<InetAddress> resolve(HttpUrl url, Duration timeout) {
        validateUrl(url.toString());
        return resolveHost(url.host(), timeout, privateOrigins.contains(origin(url)));
    }

    public List<InetAddress> resolveHost(String host, Duration timeout, boolean privateAllowed) {
        try {
            List<InetAddress> addresses = lookup(host, timeout);
            if (addresses.isEmpty()) {
                throw denied();
            }
            for (InetAddress address : addresses) {
                if (metadataOrLinkLocal(address) || (!privateAllowed && !publicAddress(address))) {
                    throw denied();
                }
            }
            return addresses;
        } catch (UnknownHostException unavailable) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_CONNECTION_FAILED",
                "无法解析连接地址，请检查地址或稍后重试。", unavailable);
        }
    }

    private List<InetAddress> lookup(String host, Duration timeout) throws UnknownHostException {
        if (!pendingLookups.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REMOTE_CONNECTION_BUSY",
                "当前连接请求较多，请稍后重试。");
        }
        var result = new CompletableFuture<List<InetAddress>>();
        Thread lookup = Thread.ofVirtual().name("外部服务地址解析").start(() -> {
            try {
                result.complete(List.copyOf(resolver.lookup(host)));
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            } finally {
                pendingLookups.release();
            }
        });
        try {
            return result.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException cancelled) {
            lookup.interrupt();
            Thread.currentThread().interrupt();
            throw timeout();
        } catch (TimeoutException expired) {
            lookup.interrupt();
            throw timeout();
        } catch (ExecutionException failed) {
            var unavailable = new UnknownHostException("连接地址无法解析");
            unavailable.initCause(failed);
            throw unavailable;
        }
    }

    private static ApiException timeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "REMOTE_TIMEOUT", "地址解析已取消或超过允许时间。");
    }

    public static boolean sameOrigin(HttpUrl left, HttpUrl right) {
        return origin(left).equals(origin(right));
    }

    private static HttpUrl parse(String value) {
        if (value == null || value.length() > 2048 || value.chars().anyMatch(c -> c <= 32 || c == 127 || c == '\\')) {
            throw denied();
        }
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty() || url.fragment() != null) {
            throw denied();
        }
        if (url.queryParameterNames().stream()
            .anyMatch(name -> name.matches("(?i).*(token|secret|password|api.?key|authorization|credential).*"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CREDENTIAL_IN_URL",
                "请通过凭据配置提供认证信息，不要写入连接地址。");
        }
        return url;
    }

    private static String origin(HttpUrl url) {
        return url.scheme() + "://" + url.host() + ":" + url.port();
    }

    private static boolean metadataOrLinkLocal(InetAddress address) {
        if (address.isLinkLocalAddress() || address.isMulticastAddress() || address.isAnyLocalAddress()
            || address.getHostAddress().equalsIgnoreCase("fd00:ec2:0:0:0:0:0:254")) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && ((u(bytes[0]) == 169 && u(bytes[1]) == 254)
            || (u(bytes[0]) == 100 && u(bytes[1]) == 100 && u(bytes[2]) == 100 && u(bytes[3]) == 200));
    }

    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            if ((u(bytes[0]) & 0xe0) != 0x20) {
                return false;
            }
            // 拒绝文档示例和可嵌入其他地址的隧道范围，不能借此访问受限的 IPv4 地址。
            return !(u(bytes[0]) == 0x20 && (u(bytes[1]) == 2 || (u(bytes[1]) == 1
                && ((u(bytes[2]) == 0 && u(bytes[3]) == 0) || (u(bytes[2]) == 0x0d && u(bytes[3]) == 0xb8)))));
        }
        int a = u(bytes[0]), b = u(bytes[1]), c = u(bytes[2]);
        return a != 0 && a != 10 && a != 127 && a < 224
            && !(a == 100 && b >= 64 && b <= 127) && !(a == 172 && b >= 16 && b <= 31)
            && !(a == 192 && ((b == 0 && (c == 0 || c == 2)) || b == 168 || (b == 88 && c == 99)))
            && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
            && !(a == 203 && b == 0 && c == 113);
    }

    private static int u(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static ApiException denied() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NETWORK_ADDRESS_DENIED",
            "连接地址不在允许的访问范围内，请检查地址或联系部署管理员。");
    }
}
