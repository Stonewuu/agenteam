package com.stonewu.agenteam.mapper.announcement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementPublicationRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementQueryRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import com.stonewu.agenteam.model.announcement.response.AnnouncementView;
import com.stonewu.agenteam.service.announcement.AnnouncementLevelCatalog;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * 公告响应只公开展示内容及当前用户的已读时间。
 */
@Component
public class AnnouncementViewMapper {

    private final AnnouncementLevelCatalog levels;

    private final AnnouncementPublicationMapper publications;

    public AnnouncementViewMapper(AnnouncementLevelCatalog levels, AnnouncementPublicationMapper publications) {
        this.levels = levels;
        this.publications = publications;
    }

    public AnnouncementView view(AnnouncementRow row) {
        return views(List.of(row)).getFirst();
    }

    /**
     * 一次读取当前页的发布名称，避免每条公告分别查询。
     */
    public List<AnnouncementView> views(List<? extends AnnouncementRow> rows) {
        var sequences = rows.stream().map(AnnouncementRow::getPublicationSequence).filter(Objects::nonNull).distinct()
            .toList();
        var publisherNames = new HashMap<Long, String>();
        if (!sequences.isEmpty()) {
            var query = Wrappers.<AnnouncementPublicationRow>lambdaQuery()
                .select(AnnouncementPublicationRow::getSequenceNo, AnnouncementPublicationRow::getPublisherName)
                .in(AnnouncementPublicationRow::getSequenceNo, sequences);
            for (var publication : publications.selectList(query)) {
                publisherNames.put(publication.getSequenceNo(), publication.getPublisherName());
            }
        }
        return rows.stream().map(row -> view(row, publisherNames.get(row.getPublicationSequence()))).toList();
    }

    private AnnouncementView view(AnnouncementRow row, String publisherName) {
        return new AnnouncementView(row.getId(), row.getScope(), row.getEnterpriseId(), row.getTitle(),
            row.getContent(), row.getContentFormat() == null ? "plain_text" : row.getContentFormat(),
            levels.require(row.getLevelCode()), Boolean.TRUE.equals(row.getEnabled()),
            row.getContentVersion().toString(), row.getRevision().toString(),
            row.getPublicationSequence() == null ? null : row.getPublicationSequence().toString(),
            row.getPublishedAt() == null ? null : row.getPublishedAt().toString(), publisherName,
            row.getCreatedAt().toString(), row.getUpdatedAt().toString(),
            row instanceof AnnouncementQueryRow query && query.getReadAt() != null ? query.getReadAt()
                .toString() : null);
    }
}
