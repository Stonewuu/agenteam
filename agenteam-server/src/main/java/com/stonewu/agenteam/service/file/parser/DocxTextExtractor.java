package com.stonewu.agenteam.service.file.parser;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * 只读取 Word 正文中的文字，不执行宏，不加载外部实体或关系中的网址。
 */
public class DocxTextExtractor {
    private static final Set<String> WORD_NAMESPACES = Set.of(
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
        "http://purl.oclc.org/ooxml/wordprocessingml/main");

    public void extract(Path path, DocumentChunkWriter writer) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            validate(zip);
            try (var input = zip.getInputStream(zip.getEntry("word/document.xml"))) {
                read(input, writer);
            }
        } catch (XMLStreamException invalid) {
            throw new DocumentParseFailure("FILE_TYPE_INVALID");
        }
    }

    private void validate(ZipFile zip) throws IOException, XMLStreamException {
        var names = new HashSet<String>();
        long expanded = 0;
        byte[] buffer = new byte[8192];
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            String name = entry.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!names.add(name) || names.size() > 10000 || name.startsWith("/") || name.contains(
                "\\") || name.contains(":")
                || Arrays.asList(name.split("/", -1)).contains("..")) {
                throw new DocumentParseFailure("FILE_TYPE_INVALID");
            }
            if (lower.contains("vbaproject") || lower.contains("activex/") || lower.startsWith("word/embeddings/")) {
                throw new DocumentParseFailure("FILE_ACTIVE_CONTENT");
            }
            if (entry.isDirectory()) {
                continue;
            }
            if (entry.getSize() > DocumentChunkWriter.MAX_EXPANDED_BYTES) {
                throw new DocumentParseFailure("FILE_EXPANDED_TOO_LARGE");
            }
            try (var input = zip.getInputStream(entry)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > DocumentChunkWriter.MAX_EXPANDED_BYTES) {
                        throw new DocumentParseFailure("FILE_EXPANDED_TOO_LARGE");
                    }
                }
            }
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("word/document.xml")) {
            throw new DocumentParseFailure("FILE_TYPE_INVALID");
        }
        boolean documentType = false;
        try (var input = zip.getInputStream(zip.getEntry("[Content_Types].xml"))) {
            var reader = xml().createXMLStreamReader(input);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    rejectDeclarations(event);
                    if (event != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String type = reader.getAttributeValue(null, "ContentType");
                    if (type != null && (type.toLowerCase(Locale.ROOT).contains("macroenabled") || type.toLowerCase(
                        Locale.ROOT).contains("vbaproject"))) {
                        throw new DocumentParseFailure("FILE_ACTIVE_CONTENT");
                    }
                    if ("/word/document.xml".equals(reader.getAttributeValue(null, "PartName"))
                        && "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml".equals(
                        type)) {
                        documentType = true;
                    }
                }
            } finally {
                reader.close();
            }
        }
        if (!documentType) {
            throw new DocumentParseFailure("FILE_TYPE_INVALID");
        }
    }

    private void read(InputStream input, DocumentChunkWriter writer) throws XMLStreamException, IOException {
        var reader = xml().createXMLStreamReader(input);
        boolean inText = false, root = false;
        int paragraph = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                rejectDeclarations(event);
                if (event == XMLStreamConstants.START_ELEMENT && reader.getNamespaceURI() != null && WORD_NAMESPACES.contains(
                    reader.getNamespaceURI())) {
                    String name = reader.getLocalName();
                    if (name.equals("document")) {
                        root = true;
                    }
                    if (name.equals("p")) {
                        paragraph++;
                        writer.paragraphLocation("第 " + paragraph + " 段起");
                    }
                    if (name.equals("t")) {
                        inText = true;
                    }
                    if (name.equals("tab")) {
                        writer.write("\t");
                    }
                    if (name.equals("br")) {
                        writer.write("\n");
                    }
                    if (Set.of("altChunk", "object").contains(name)) {
                        throw new DocumentParseFailure("FILE_ACTIVE_CONTENT");
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && reader.getNamespaceURI() != null && WORD_NAMESPACES.contains(
                    reader.getNamespaceURI())) {
                    if (reader.getLocalName().equals("t")) {
                        inText = false;
                    }
                    if (reader.getLocalName().equals("p")) {
                        writer.paragraph();
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inText) {
                    writer.write(reader.getText());
                }
            }
        } finally {
            reader.close();
        }
        if (!root) {
            throw new DocumentParseFailure("FILE_TYPE_INVALID");
        }
    }

    private XMLInputFactory xml() {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("禁止读取外部实体");
        });
        return factory;
    }

    private void rejectDeclarations(int event) throws DocumentParseFailure {
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
            throw new DocumentParseFailure("FILE_TYPE_INVALID");
        }
    }
}
