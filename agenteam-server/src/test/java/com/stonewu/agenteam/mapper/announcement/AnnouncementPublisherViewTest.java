package com.stonewu.agenteam.mapper.announcement;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementPublicationRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import com.stonewu.agenteam.service.announcement.AnnouncementLevelCatalog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnnouncementPublisherViewTest {
    private final AnnouncementPublicationMapper publications = mock(AnnouncementPublicationMapper.class);
    private final AnnouncementViewMapper views = new AnnouncementViewMapper(new AnnouncementLevelCatalog(), publications);

    @BeforeEach
    void prepare() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AnnouncementPublicationRow.class);
    }

    @Test
    void loadsPublisherNamesForTheEntirePageInOneQuery() {
        when(publications.selectList(any(Wrapper.class))).thenReturn(List.of(publication(12, "李四"), publication(11, "张三")));
        var result = views.views(List.of(announcement("first", 11L), announcement("second", 12L), announcement("draft", null)));
        assertEquals("张三", result.get(0).publisherName());
        assertEquals("李四", result.get(1).publisherName());
        assertNull(result.get(2).publisherName());
        verify(publications).selectList(any(Wrapper.class));
        verifyNoMoreInteractions(publications);
    }

    @Test
    void doesNotQueryPublicationForDrafts() {
        assertNull(views.view(announcement("draft", null)).publisherName());
        verifyNoInteractions(publications);
    }

    @Test
    void neverUsesCreatorOrLastEditorAsMissingPublisher() {
        when(publications.selectList(any(Wrapper.class))).thenReturn(List.of(publication(11, null)));
        assertNull(views.view(announcement("historical", 11L)).publisherName());
    }

    private AnnouncementPublicationRow publication(long sequence, String name) {
        var row = new AnnouncementPublicationRow();
        row.setSequenceNo(sequence);
        row.setPublisherName(name);
        return row;
    }

    private AnnouncementRow announcement(String id, Long sequence) {
        var row = new AnnouncementRow();
        row.setId(id);
        row.setScope("platform");
        row.setTitle("测试公告");
        row.setContent("正文");
        row.setContentFormat("plain_text");
        row.setLevelCode("important");
        row.setEnabled(sequence != null);
        row.setContentVersion(1L);
        row.setRevision(1L);
        row.setPublicationSequence(sequence);
        row.setPublishedAt(sequence == null ? null : Instant.EPOCH);
        row.setCreatedAt(Instant.EPOCH);
        row.setUpdatedAt(Instant.EPOCH);
        row.setCreatedBy("creator");
        row.setUpdatedBy("last-editor");
        return row;
    }
}
