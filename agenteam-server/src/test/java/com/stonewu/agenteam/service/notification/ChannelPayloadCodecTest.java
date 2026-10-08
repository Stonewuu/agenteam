package com.stonewu.agenteam.service.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelPayloadCodecTest {
    private final ChannelPayloadCodec codec = new ChannelPayloadCodec(new ObjectMapper());

    @Test
    void databaseObjectKeyReorderingDoesNotChangeTheStoredDigest() {
        var rendered = codec.decode("{\"touser\":\"member\",\"textcard\":{\"url\":\"https://example.test/notification\",\"title\":\"工作提醒\"}}");
        var reordered = codec.decode("{\"textcard\":{\"title\":\"工作提醒\",\"url\":\"https://example.test/notification\"},\"touser\":\"member\"}");
        assertEquals(codec.hash(rendered), codec.hash(reordered));
        assertEquals(codec.encode(rendered), codec.encode(reordered));
    }

    @Test
    void recipientAndContentOrderChangesAreDetected() {
        assertNotEquals(codec.hash(codec.decode("{\"receive_id\":\"one\",\"parts\":[1,2]}")),
            codec.hash(codec.decode("{\"receive_id\":\"two\",\"parts\":[1,2]}")));
        assertNotEquals(codec.hash(codec.decode("{\"parts\":[1,2]}")), codec.hash(codec.decode("{\"parts\":[2,1]}")));
    }
}
