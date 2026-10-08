package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.mapper.export.CsvExportMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.export.entity.ExportSnapshot;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.service.file.FileContentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 数据读取完成后才写私有文件，未登记的失败结果由调用方清理。
 */
@Service
public class ExportFileService {
    private final FileContentStorage storage;
    private final CsvExportMapper csv;
    private final FileMapper files;

    public ExportFileService(FileContentStorage storage, CsvExportMapper csv, FileMapper files) {
        this.storage = storage;
        this.csv = csv;
        this.files = files;
    }

    public PreparedGeneratedFile write(String enterprise, String user, ExportSnapshot snapshot,
                                       BooleanSupplier active) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("导出文件写入不能占用数据库事务");
        }
        try (var input = csv.open(snapshot, active)) {
            var saved = storage.write(enterprise, input, CsvExportMapper.MAX_BYTES);
            return new PreparedGeneratedFile(UUID.randomUUID().toString(), enterprise, user, null, null, null, "export",
                snapshot.fileName(), "text/csv", saved.key(), saved.size(), saved.sha256());
        } catch (IOException failed) {
            throw new IllegalStateException("导出文件无法保存", failed);
        }
    }

    public void discard(PreparedGeneratedFile file) {
        if (!files.referencesKey(file.storageKey())) {
            storage.delete(file.storageKey());
        }
    }
}
