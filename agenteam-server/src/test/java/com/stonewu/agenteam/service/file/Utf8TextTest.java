package com.stonewu.agenteam.service.file;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8TextTest {
    @Test
    void countsTheSameBytesAsTheJdkWithoutAllocatingAnEncodedCopy() {
        var random = new Random(42);
        for (int sample = 0; sample < 100; sample++) {
            var text = new StringBuilder("普通文字🙂\ud800x\udc00");
            for (int index = 0; index < 1000; index++) {
                text.append((char) random.nextInt(65536));
            }
            String value = text.toString();
            assertEquals(value.getBytes(StandardCharsets.UTF_8).length, Utf8Text.size(value));
            String prefix = Utf8Text.prefix(value, 201);
            assertEquals(prefix.getBytes(StandardCharsets.UTF_8).length, Utf8Text.size(prefix));
        }
    }
}
