package com.stonewu.agenteam.mapper.file;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.model.file.entity.AttachmentText;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.file.entity.FileTextRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Optional;

/**
 * 解析文字与原文件摘要绑定，只供当前已验证文件使用。
 */
@Mapper
public interface FileTextMapper extends BaseMapper<FileTextRow> {
    default void save(FileRecord file, AttachmentText content, Instant now) {
        FileTextRow row = new FileTextRow();
        row.setEnterpriseId(file.enterpriseId());
        row.setFileId(file.id());
        row.setSha256(file.sha256());
        row.setContentText(content.text());
        row.setTruncated(content.truncated() ? 1 : 0);
        row.setCreatedAt(now);
        insert(row);
    }

    default Optional<AttachmentText> find(FileRecord file) {
        return Optional.ofNullable(selectOne(Wrappers.<FileTextRow>lambdaQuery()
                .eq(FileTextRow::getEnterpriseId, file.enterpriseId()).eq(FileTextRow::getFileId, file.id())
                .eq(FileTextRow::getSha256, file.sha256())))
            .map(row -> new AttachmentText(row.getContentText(), row.getTruncated() != 0));
    }
}
