package com.stonewu.agenteam.service.file;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

/**
 * 缓存固定文本的字节和行号；调用者必须在每次取用前完成当前权限与文件有效期检查。
 */
public final class TextDocumentCache {
    private record Entry(TextFileDocument document, long created) {
    }

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final long maximum;
    private long bytes;

    public TextDocumentCache(long maximum) {
        this.maximum = maximum;
    }

    public TextFileDocument get(String key, Supplier<TextFileDocument> loader) {
        synchronized (this) {
            clearExpired();
            var found = entries.get(key);
            if (found != null) {
                return found.document();
            }
        }
        var loaded = loader.get();
        if (loaded.memoryBytes() > maximum) {
            return loaded;
        }
        synchronized (this) {
            var previous = entries.remove(key);
            if (previous != null) {
                bytes -= previous.document().memoryBytes();
            }
            while (!entries.isEmpty() && (bytes + loaded.memoryBytes() > maximum || entries.size() >= 128)) {
                removeFirst();
            }
            entries.put(key, new Entry(loaded, System.nanoTime()));
            bytes += loaded.memoryBytes();
        }
        return loaded;
    }

    private void clearExpired() {
        long now = System.nanoTime();
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.created() > 60_000_000_000L) {
                bytes -= entry.document().memoryBytes();
                iterator.remove();
            }
        }
    }

    private void removeFirst() {
        var first = entries.pollFirstEntry();
        bytes -= first.getValue().document().memoryBytes();
    }
}
