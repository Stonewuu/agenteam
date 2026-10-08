package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.file.response.FileTextSlice;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class TextFileDocumentTest {
    @Test
    void reconstructsChineseEmojiCrLfAndLongLinesWithoutLoss() {
        String original = "开头\r\n" + "中文🙂abc".repeat(4000) + "\r\n\n末尾\n";
        var file = file(original);
        StringBuilder actual = new StringBuilder();
        String cursor = null;
        int expectedOffset = 0, pages = 0;
        do {
            FileTextSlice page = file.read(1, null, cursor, 2, 513);
            assertEquals(expectedOffset, page.startOffset());
            assertTrue(page.content().getBytes(StandardCharsets.UTF_8).length <= 513);
            assertFalse(page.content().contains("�"));
            actual.append(page.content());
            expectedOffset = page.endOffset();
            cursor = page.nextCursor();
            assertTrue(++pages < 1000);
            if (cursor != null) {
                assertFalse(page.eof());
            }
        } while (cursor != null);
        assertEquals(original, actual.toString());
        assertEquals(original.getBytes(StandardCharsets.UTF_8).length, expectedOffset);
    }

    @Test
    void distinguishesFinishingRequestedLinesFromReadingTheWholeFile() {
        var file = file("一\r\n二\n三\n四");
        var range = file.read(2, 3, null, 200, 8192);
        assertEquals("二\n三\n", range.content());
        assertEquals(2, range.startLine());
        assertEquals(3, range.endLine());
        assertTrue(range.rangeComplete());
        assertFalse(range.eof());
        assertNull(range.nextCursor());
        var last = file.read(4, 100, null, 200, 8192);
        assertEquals("四", last.content());
        assertTrue(last.eof());
    }

    @Test
    void rejectsChangedFileAndInvalidContinuation() {
        String cursor = file("很长的原文".repeat(50)).read(1, null, null, 200, 16).nextCursor();
        var changed = new TextFileDocument("tool-results/a/content.txt", "version-2", "不同原文".repeat(50));
        assertThrows(ApiException.class, () -> changed.read(1, null, cursor, 200, 16));
        assertThrows(ApiException.class, () -> file("abc").read(1, null, "%%%", 200, 16));
        assertThrows(ApiException.class, () -> file("abc").read(0, null, null, 200, 16));
        assertThrows(ApiException.class, () -> file("abc").read(3, null, null, 200, 16));
    }

    @Test
    void readsEmptyFileAndPreservesTrailingNewline() {
        var empty = file("").read(1, null, null, 200, 16);
        assertEquals("", empty.content());
        assertEquals(0, empty.totalLines());
        assertTrue(empty.eof());
        assertEquals("a\n", file("a\n").read(1, null, null, 200, 16).content());
    }

    @Test
    void browserWindowsCanMoveBothWaysAcrossUnicodeBoundaries() {
        String original = "中文🙂\r\nabc".repeat(40);
        var file = file(original);
        StringBuilder forward = new StringBuilder(), backward = new StringBuilder();
        int offset = 0;
        while (offset < file.sizeBytes()) {
            var part = file.window(offset, 13, false);
            assertEquals(offset, part.startOffset());
            assertTrue(part.endOffset() > offset);
            forward.append(part.content());
            offset = part.endOffset();
        }
        while (offset > 0) {
            var part = file.window(offset, 13, true);
            assertEquals(offset, part.endOffset());
            assertTrue(part.startOffset() < offset);
            backward.insert(0, part.content());
            offset = part.startOffset();
        }
        assertEquals(original, forward.toString());
        assertEquals(original, backward.toString());
    }

    @Test
    void paginatesMatchesWithContextAndHonorsLiteralPatterns() {
        var file = file("first\nA.b\ncontext\naXb\na.b\nlast");
        var first = file.search("a.b", false, true, 1, 1, 1, 1, 2048, () -> false);
        assertEquals(1, first.matches().size());
        assertEquals(2, first.matches().getFirst().line());
        assertEquals("first\nA.b\ncontext\n", first.matches().getFirst().excerpt().content());
        assertFalse(first.complete());
        var next = file.search("a.b", false, true, first.nextLine(), 1, 0, 0, 2048, () -> false);
        assertEquals(5, next.matches().getFirst().line());
        assertTrue(next.complete());
        var regex = file.search("a.b", true, true, 1, 10, 0, 0, 2048, () -> false);
        assertEquals(3, regex.matches().size());
    }

    @Test
    void locatesMatchesAtTheEndOfVeryLongLines() {
        var file = file("中文".repeat(20000) + "needle🙂结尾");
        var found = file.search("needle", false, false, 1, 10, 0, 0, 512, () -> false);
        var match = found.matches().getFirst();
        assertTrue(match.excerpt().content().contains("needle🙂结尾"));
        assertTrue(match.matchOffset() > 100000);
        assertTrue(file.read(1, null, match.readCursor(), 200, 512).content().contains("needle"));
    }

    @Test
    void boundsCombinedSearchOutputAndCanContinue() {
        var file = file(("word " + "文".repeat(700) + "\n").repeat(30));
        var seen = new ArrayList<Integer>();
        int nextLine = 1;
        do {
            var found = file.search("word", false, false, nextLine, 100, 0, 0, 1024, () -> false);
            assertTrue(found.matches().stream().mapToInt(value -> Utf8Text.size(value.excerpt().content())).sum() <= 1024);
            found.matches().forEach(value -> seen.add(value.line()));
            nextLine = found.nextLine();
        } while (nextLine != 0);
        assertEquals(30, seen.size());
        assertEquals(30, seen.stream().distinct().count());
    }

    @Test
    void boundsRegexWorkAndRespondsToCancellation() {
        var file = file("a".repeat(100000) + "!");
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(file.search("(a+)+$", true, false, 1, 10, 0, 0, 2048, () -> false).matches().isEmpty()));
        assertThrows(ApiException.class, () -> file.search("a", false, false, 1, 10, 0, 0, 2048, () -> true));
        assertThrows(ApiException.class, () -> file.search("(?<=a)b", true, false, 1, 10, 0, 0, 2048, () -> false));
    }

    private TextFileDocument file(String text) {
        return new TextFileDocument("tool-results/a/content.txt", "version-1", text);
    }
}
