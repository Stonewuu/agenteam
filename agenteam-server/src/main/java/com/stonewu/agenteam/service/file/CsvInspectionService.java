package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.file.entity.CsvProfile;
import com.stonewu.agenteam.model.file.entity.CsvRow;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 完整 CSV 由独立进程解析，原始行分批提交，最后才允许文件进入可用状态。
 */
@Service
public class CsvInspectionService {
    private final DocumentParserProcess parser;
    private final CsvInspectionTransactions transactions;

    public CsvInspectionService(DocumentParserProcess parser, CsvInspectionTransactions transactions) {
        this.parser = parser;
        this.transactions = transactions;
    }

    public CsvProfile parse(JobLease lease, FileRecord file, BooleanSupplier current) {
        transactions.start(lease, file);
        try (var parsed = parser.parse(file, current)) {
            List<CsvRow> batch = new ArrayList<>();
            parsed.forEachCsv(row -> {
                if (!current.getAsBoolean()) {
                    throw DocumentParserProcess.failure("FILE_PROCESSING_CANCELLED");
                }
                batch.add(row);
                if (batch.size() == 100) {
                    transactions.append(lease, file, List.copyOf(batch));
                    batch.clear();
                }
            });
            if (!batch.isEmpty()) {
                transactions.append(lease, file, batch);
            }
            return parsed.csv();
        }
    }
}
