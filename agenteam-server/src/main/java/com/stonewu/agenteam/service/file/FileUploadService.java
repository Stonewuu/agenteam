package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.UUID;

/**
 * 上传先取得短期占用，再读取正文；保存文件期间没有数据库事务。
 */
@Service
public class FileUploadService {
    private final FileUploadTransactions transactions;
    private final FileContentStorage storage;
    private final FileMapper files;

    public FileUploadService(FileUploadTransactions transactions, FileContentStorage storage, FileMapper files) {
        this.transactions = transactions;
        this.storage = storage;
        this.files = files;
    }

    public void upload(AuthContext actor, String id, long declaredLength, InputStream body) {
        String lease = UUID.randomUUID().toString();
        var file = transactions.claim(actor, id, lease);
        try {
            if (declaredLength >= 0 && declaredLength != file.expectedSizeBytes()) {
                throw mismatch();
            }
            var saved = storage.write(file.enterpriseId(), body, FileUploadPolicy.maxBytes(file.purpose()));
            if (saved.size() != file.expectedSizeBytes() || !saved.sha256().equals(file.expectedSha256())) {
                storage.delete(saved.key());
                throw mismatch();
            }
            // 提交结果不明确时保留完整文件，后续清理只删除数据库没有引用的文件。
            transactions.uploaded(actor, file.id(), lease, saved);
        } finally {
            files.releaseUpload(file.enterpriseId(), file.id(), lease);
        }
    }

    private static ApiException mismatch() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_CONTENT_MISMATCH",
            "上传内容与选择的文件不一致，请重新上传。");
    }
}
