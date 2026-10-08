package com.stonewu.agenteam.service.operations;

import com.stonewu.agenteam.mapper.operations.OperationalStateMapper;
import com.stonewu.agenteam.model.operations.entity.OperationalSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class OperationalQueryService {
    private final OperationalStateMapper states;
    private final Clock clock;

    public OperationalQueryService(OperationalStateMapper states, Clock clock) {
        this.states = states;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public OperationalSnapshot read() {
        return states.read(clock.instant());
    }
}
