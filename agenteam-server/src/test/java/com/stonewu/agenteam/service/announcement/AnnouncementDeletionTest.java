package com.stonewu.agenteam.service.announcement;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.announcement.AnnouncementPublicationMapper;
import com.stonewu.agenteam.mapper.announcement.AnnouncementSqlMapper;
import com.stonewu.agenteam.mapper.announcement.AnnouncementViewMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementPublicationRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnnouncementDeletionTest {
    private final AnnouncementSqlMapper rows = mock(AnnouncementSqlMapper.class);
    private final AnnouncementPublicationMapper publications = mock(AnnouncementPublicationMapper.class);
    private final AnnouncementViewMapper views = mock(AnnouncementViewMapper.class);
    private final AuthMapper users = mock(AuthMapper.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private final Instant now = Instant.parse("2026-09-22T00:00:00Z");
    private final UserEntity actor = new UserEntity("admin", "admin", "", "管理员", "active", true, null,
        null, null, 1, null, 1, now, now);
    private final AnnouncementRow row = new AnnouncementRow();
    private AnnouncementManagementService service;

    @BeforeEach
    void prepare() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AnnouncementRow.class);
        service = new AnnouncementManagementService(rows, publications, null, views, null, users, null,
            mock(EnterpriseMapper.class), mock(EnterpriseTableMapper.class), null, null, audit,
            Clock.fixed(now, ZoneOffset.UTC), new AnnouncementContent(new ObjectMapper()));
        when(users.findById(actor.id())).thenReturn(Optional.of(actor));
        row.setId("announcement");
        row.setScope("platform");
        row.setEnabled(true);
        row.setRevision(7L);
        when(rows.lock(row.getId())).thenReturn(row);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deletesPublishedAnnouncementAndRecordsAudit() {
        when(rows.update(any(LambdaUpdateWrapper.class))).thenReturn(1);
        service.delete(actor, null, true, row.getId(), 7);
        ArgumentCaptor<LambdaUpdateWrapper<AnnouncementRow>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(rows).update(update.capture());
        var values = update.getValue().getParamNameValuePairs().values();
        assertTrue(values.contains(now));
        assertTrue(values.contains(false));
        verify(audit).record(null, actor, "announcement.delete", "announcement", row.getId(), "删除公告", Map.of());
    }

    @Test
    void rejectsChangedVersionAndOtherEnterprise() {
        assertEquals(409, assertThrows(ApiException.class, () -> service.delete(actor, null, true, row.getId(), 6)).getStatusCode().value());
        row.setEnterpriseId("another-enterprise");
        assertEquals(404, assertThrows(ApiException.class, () -> service.delete(actor, null, true, row.getId(), 7)).getStatusCode().value());
        verify(rows, never()).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void rejectsNonSuperAdministratorOnPlatformEndpoint() {
        var ordinary = new UserEntity(actor.id(), "member", "", "成员", "active", false, null, null, null, 1, null, 1, now, now);
        when(users.findById(actor.id())).thenReturn(Optional.of(ordinary));
        assertEquals(403, assertThrows(ApiException.class, () -> service.delete(ordinary, null, true, row.getId(), 7)).getStatusCode().value());
        verify(rows, never()).lock(any());
    }

    @Test
    void deletedAnnouncementCannotBeEditedOrEnabledAgain() {
        row.setDeletedAt(now);
        assertEquals(404, assertThrows(ApiException.class, () -> service.status(actor, null, true, row.getId(), 7, true)).getStatusCode().value());
        assertEquals(404, assertThrows(ApiException.class, () -> service.delete(actor, null, true, row.getId(), 7)).getStatusCode().value());
        verify(rows, never()).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void firstPublicationStoresTheActingUsersName() {
        row.setEnabled(false);
        row.setContentVersion(1L);
        when(rows.update(any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(rows.selectById(row.getId())).thenReturn(row);
        when(publications.insert(any(AnnouncementPublicationRow.class))).thenAnswer(call -> {
            AnnouncementPublicationRow created = call.getArgument(0);
            created.setSequenceNo(11L);
            return 1;
        });
        service.status(actor, null, true, row.getId(), 7, true);
        var saved = ArgumentCaptor.forClass(AnnouncementPublicationRow.class);
        verify(publications).insert(saved.capture());
        assertEquals(actor.displayName(), saved.getValue().getPublisherName());
        assertEquals(now, saved.getValue().getPublishedAt());
    }

    @Test
    void enablingTheSameVersionKeepsItsOriginalPublisher() {
        row.setEnabled(false);
        row.setContentVersion(1L);
        var published = new AnnouncementPublicationRow();
        published.setSequenceNo(11L);
        published.setPublisherName("最初的发布人");
        published.setPublishedAt(now.minusSeconds(60));
        when(publications.selectOne(any(Wrapper.class))).thenReturn(published);
        when(rows.update(any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(rows.selectById(row.getId())).thenReturn(row);
        service.status(actor, null, true, row.getId(), 7, true);
        assertEquals("最初的发布人", published.getPublisherName());
        assertEquals(now.minusSeconds(60), published.getPublishedAt());
        verify(publications, never()).insert(any(AnnouncementPublicationRow.class));
    }
}
