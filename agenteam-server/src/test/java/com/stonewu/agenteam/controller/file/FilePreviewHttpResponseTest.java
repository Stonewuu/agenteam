package com.stonewu.agenteam.controller.file;

import com.stonewu.agenteam.mapper.file.FilePreviewMapper;
import com.stonewu.agenteam.model.file.entity.OpenedPreviewFile;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilePreviewHttpResponseTest {
    private MockHttpServletResponse read(String name, String range, String ifRange, String method, boolean download) throws Exception {
        byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        var metadata = FilePreviewMapper.view("file", name, name, false, "workspace", null, bytes.length, "2026-09-19", "revision");
        var request = new MockHttpServletRequest(method, "/files/file/content");
        if (range != null) {
            request.addHeader("Range", range);
        }
        if (ifRange != null) {
            request.addHeader("If-Range", ifRange);
        }
        var response = new MockHttpServletResponse();
        try (var opened = new OpenedPreviewFile(metadata, new ByteArrayInputStream(bytes))) {
            FilePreviewHttpResponse.write(opened, download, request, response, response.getOutputStream());
        }
        return response;
    }

    @Test
    void mediaRequestsReceiveTheExactRangeAndLength() throws Exception {
        var partial = read("片段.mp4", "bytes=2-5", null, "GET", false);
        assertEquals(206, partial.getStatus());
        assertEquals("2345", partial.getContentAsString());
        assertEquals("bytes 2-5/10", partial.getHeader("Content-Range"));
        assertEquals(4, partial.getContentLength());
        assertEquals("video/mp4", partial.getContentType());
        assertTrue(partial.getHeader("Content-Disposition").startsWith("inline"));
        assertEquals("789", read("clip.mp4", "bytes=-3", null, "GET", false).getContentAsString());
        assertEquals("789", read("clip.mp4", "bytes=7-", null, "GET", false).getContentAsString());
    }

    @Test
    void rejectsInvalidRangesAndHonorsChangedVersion() throws Exception {
        for (String range : new String[]{"bytes=99-", "bytes=5-2", "broken"}) {
            var response = read("clip.mp4", range, null, "GET", false);
            assertEquals(416, response.getStatus());
            assertEquals("bytes */10", response.getHeader("Content-Range"));
            assertEquals(0, response.getContentAsByteArray().length);
        }
        var changed = read("clip.mp4", "bytes=2-5", "\"old\"", "GET", false);
        assertEquals(200, changed.getStatus());
        assertEquals("0123456789", changed.getContentAsString());
        assertEquals("2345", read("clip.mp4", "bytes=2-5", "\"revision\"", "GET", false).getContentAsString());
    }

    @Test
    void headReturnsOnlyHeadersAndDownloadNeverRunsActiveContent() throws Exception {
        var head = read("clip.mp4", "bytes=2-5", null, "HEAD", false);
        assertEquals(206, head.getStatus());
        assertEquals(4, head.getContentLength());
        assertEquals(0, head.getContentAsByteArray().length);
        for (String name : new String[]{"page.html", "image.svg", "clip.mp4"}) {
            var response = read(name, null, null, "GET", name.endsWith(".mp4"));
            assertTrue(response.getHeader("Content-Disposition").startsWith("attachment"));
            assertEquals("application/octet-stream", response.getContentType());
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
            assertTrue(response.getHeader("Content-Security-Policy").contains("sandbox"));
        }
    }
}
