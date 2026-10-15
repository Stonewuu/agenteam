package com.stonewu.agenteam.service.chat.provider.feishu;

import com.lark.oapi.google.protobuf.ByteString;
import com.lark.oapi.ws.pb.Pbbp2;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** 使用官方开发包帧模型，分片合并限制数量、总大小及保存时间。 */
public final class FeishuFrameCodec {
    private record Parts(Instant expiresAt, byte[][] bytes) {
    }
    private final Map<String, Parts> fragments = new HashMap<>();

    public synchronized byte[] payload(Pbbp2.Frame frame, Instant now) {
        fragments.values().removeIf(value -> !value.expiresAt().isAfter(now));
        int sum = number(frame, "sum");
        int seq = number(frame, "seq");
        String id = header(frame, "message_id");
        byte[] payload = frame.getPayload().toByteArray();
        if (sum < 1 || sum > 32 || seq < 0 || seq >= sum || id.length() > 191 || payload.length > 1_048_576) {
            throw new IllegalArgumentException("飞书分片字段超出允许范围");
        }
        if (sum == 1) {
            return payload;
        }
        if (fragments.size() >= 32 && !fragments.containsKey(id)) {
            throw new IllegalArgumentException("飞书未完成分片过多");
        }
        var part = fragments.computeIfAbsent(id, key -> new Parts(now.plusSeconds(30), new byte[sum][]));
        if (part.bytes().length != sum || part.bytes()[seq] != null && !Arrays.equals(part.bytes()[seq], payload)) {
            fragments.remove(id);
            throw new IllegalArgumentException("飞书相同分片编号内容发生变化");
        }
        part.bytes()[seq] = payload;
        long size = fragments.values().stream().flatMap(value -> Arrays.stream(value.bytes()))
            .filter(value -> value != null).mapToLong(value -> value.length).sum();
        if (size > 4_194_304 || Arrays.stream(part.bytes()).filter(value -> value != null).mapToInt(value -> value.length).sum() > 1_048_576) {
            fragments.remove(id);
            throw new IllegalArgumentException("飞书分片内容超过允许大小");
        }
        if (Arrays.stream(part.bytes()).anyMatch(value -> value == null)) {
            return null;
        }
        var result = new ByteArrayOutputStream();
        for (byte[] bytes : part.bytes()) {
            result.writeBytes(bytes);
        }
        fragments.remove(id);
        return result.toByteArray();
    }

    public static byte[] acknowledge(Pbbp2.Frame original, int code, long elapsedMillis) {
        return acknowledge(original, code, elapsedMillis, null);
    }

    public static byte[] acknowledge(Pbbp2.Frame original, int code, long elapsedMillis, byte[] response) {
        String data = response == null ? "" : ",\"data\":\"" + Base64.getEncoder().encodeToString(response) + "\"";
        return original.toBuilder().setPayload(ByteString.copyFrom(("{\"code\":" + code + data + "}").getBytes(StandardCharsets.UTF_8)))
            .addHeaders(Pbbp2.Header.newBuilder().setKey("biz_rt").setValue(Long.toString(Math.max(0, elapsedMillis))))
            .build().toByteArray();
    }

    public static byte[] ping(int serviceId) {
        return Pbbp2.Frame.newBuilder().setSeqID(0).setLogID(0).setService(serviceId).setMethod(0)
            .addHeaders(Pbbp2.Header.newBuilder().setKey("type").setValue("ping")).build().toByteArray();
    }

    public static String header(Pbbp2.Frame frame, String key) {
        return frame.getHeadersList().stream().filter(value -> value.getKey().equals(key))
            .map(Pbbp2.Header::getValue).findFirst().orElseThrow(() -> new IllegalArgumentException("飞书帧缺少必要字段"));
    }

    private int number(Pbbp2.Frame frame, String key) {
        String value = header(frame, key);
        if (!value.matches("[0-9]{1,3}")) {
            throw new IllegalArgumentException("飞书分片编号无效");
        }
        return Integer.parseInt(value);
    }
}
