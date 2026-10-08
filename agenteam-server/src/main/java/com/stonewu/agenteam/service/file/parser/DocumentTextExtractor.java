package com.stonewu.agenteam.service.file.parser;

import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * 文件格式与真实内容必须相符，原文位置来自解析结果。
 */
public class DocumentTextExtractor {
    public Integer extract(Path source, String format, DocumentChunkWriter writer) throws IOException {
        byte[] signature;
        try (var input = Files.newInputStream(source)) {
            signature = input.readNBytes(8);
        }
        String start = new String(signature, StandardCharsets.ISO_8859_1);
        if (format.equals("pdf")) {
            if (!start.startsWith("%PDF-")) {
                throw new DocumentParseFailure("FILE_TYPE_INVALID");
            }
            return pdf(source, writer);
        }
        if (format.equals("docx")) {
            if (!start.startsWith("PK\003\004")) {
                throw new DocumentParseFailure("FILE_TYPE_INVALID");
            }
            new DocxTextExtractor().extract(source, writer);
            return null;
        }
        if ((!format.equals("txt") && !format.equals("md")) || start.startsWith("%PDF-") || start.startsWith(
            "PK") || start.startsWith("MZ")) {
            throw new DocumentParseFailure("FILE_TYPE_INVALID");
        }
        writer.location(null, "正文");
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(Files.newInputStream(source), decoder)) {
            char[] buffer = new char[8192];
            int read;
            boolean first = true;
            while ((read = reader.read(buffer)) != -1) {
                int offset = first && read > 0 && buffer[0] == '\uFEFF' ? 1 : 0;
                first = false;
                for (int i = offset; i < read; i++) {
                    if (buffer[i] == '\n') {
                        writer.paragraph();
                    } else {
                        writer.write(buffer, i, 1);
                    }
                }
            }
        }
        return null;
    }

    private int pdf(Path path, DocumentChunkWriter writer) throws IOException {
        try (var input = new RandomAccessReadBufferedFile(path)) {
            var parser = new PDFParser(input);
            try (var document = parser.parse(false)) {
                if (document.isEncrypted()) {
                    throw new DocumentParseFailure("FILE_ENCRYPTED");
                }
                var keys = new ArrayList<>(document.getDocument().getXrefTable().keySet());
                if (keys.size() > 200000 || document.getNumberOfPages() > 10000) {
                    throw new DocumentParseFailure("FILE_EXPANDED_TOO_LARGE");
                }
                long expanded = 0;
                byte[] buffer = new byte[8192];
                for (var key : keys) {
                    if (document.getDocument().getObjectFromPool(key).getObject() instanceof COSStream stream) {
                        try (var content = stream.createInputStream()) {
                            int read;
                            while ((read = content.read(buffer)) != -1) {
                                expanded += read;
                                if (expanded > DocumentChunkWriter.MAX_EXPANDED_BYTES) {
                                    throw new DocumentParseFailure("FILE_EXPANDED_TOO_LARGE");
                                }
                            }
                        }
                    }
                }
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    writer.location(page, null);
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    stripper.writeText(document, writer);
                }
                return document.getNumberOfPages();
            }
        } catch (InvalidPasswordException encrypted) {
            throw new DocumentParseFailure("FILE_ENCRYPTED");
        }
    }
}
