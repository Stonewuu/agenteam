package com.stonewu.agenteam.service.memory;

import com.stonewu.agenteam.mapper.memory.MemoryMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class MemoryRetentionService {
    private final MemoryMapper memories;
    private final Clock clock;

    public MemoryRetentionService(MemoryMapper memories, Clock clock) {
        this.memories = memories;
        this.clock = clock;
    }

    public void clean() {
        var now = clock.instant();
        for (int batch = 0; batch < 10; batch++) {
            if (memories.expire(now) < 500) {
                break;
            }
        }
    }
}
