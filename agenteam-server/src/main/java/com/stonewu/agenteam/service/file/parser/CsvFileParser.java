package com.stonewu.agenteam.service.file.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.file.entity.CsvProfile;
import com.stonewu.agenteam.model.file.entity.CsvRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.apache.commons.csv.QuoteMode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 在受限子进程内完整解析，不计算公式，不执行字段中的代码。
 */
public class CsvFileParser {
    private final ObjectMapper json = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(10 * 1024 * 1024).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public CsvProfile parse(Path source, Path destination) throws IOException {
        if (Files.size(source) > 10L * 1024 * 1024) {
            throw new DocumentParseFailure("FILE_TOO_LARGE");
        }
        var format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setAllowMissingColumnNames(false)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setIgnoreEmptyLines(false).setLenientEof(false)
            .setTrailingData(false)
            .setNullString("").setQuoteMode(QuoteMode.ALL_NON_NULL).get();
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new PushbackReader(new InputStreamReader(Files.newInputStream(source), decoder), 1);
             var output = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            int first = reader.read();
            if (first != -1 && first != '\uFEFF') {
                reader.unread(first);
            }
            try (var parser = format.parse(reader)) {
                var names = parser.getHeaderNames();
                if (names.isEmpty() || names.size() > 100) {
                    throw new DocumentParseFailure("CSV_COLUMN_LIMIT");
                }
                for (String name : names) {
                    if (name.isBlank() || name.codePointCount(0, name.length()) > 128 || name.codePoints()
                        .anyMatch(Character::isISOControl)) {
                        throw new DocumentParseFailure("CSV_HEADER_INVALID");
                    }
                }
                String[] types = new String[names.size()];
                boolean[] nullable = new boolean[names.size()];
                long row = 0, expanded = 0;
                for (var record : parser) {
                    if (++row > 100000) {
                        throw new DocumentParseFailure("CSV_ROW_LIMIT");
                    }
                    if (record.size() != names.size()) {
                        throw new DocumentParseFailure("CSV_COLUMN_COUNT_MISMATCH");
                    }
                    List<String> values = new ArrayList<>();
                    for (int i = 0; i < names.size(); i++) {
                        String value = record.get(i);
                        values.add(value);
                        if (value == null) {
                            nullable[i] = true;
                        } else {
                            if (value.codePoints().anyMatch(point -> Character.isISOControl(
                                point) && point != '\n' && point != '\r' && point != '\t')) {
                                throw new DocumentParseFailure("FILE_TYPE_INVALID");
                            }
                            types[i] = merge(types[i], infer(value));
                        }
                    }
                    String line = json.writeValueAsString(new CsvRow(row, values));
                    expanded += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (expanded > DocumentChunkWriter.MAX_EXPANDED_BYTES) {
                        throw new DocumentParseFailure("FILE_EXPANDED_TOO_LARGE");
                    }
                    output.write(line);
                    output.newLine();
                }
                var columns = new ArrayList<CsvProfile.Column>();
                for (int i = 0; i < names.size(); i++) {
                    columns.add(
                        new CsvProfile.Column(names.get(i), types[i] == null ? "string" : types[i], nullable[i]));
                }
                return new CsvProfile(List.copyOf(columns), row);
            }
        } catch (DocumentParseFailure failure) {
            throw failure;
        } catch (RuntimeException | IOException invalid) {
            throw new DocumentParseFailure("CSV_FORMAT_INVALID");
        }
    }

    private String merge(String previous, String value) {
        if (previous == null || previous.equals(value)) {
            return value;
        }
        if ((previous.equals("integer") && value.equals("decimal")) || (previous.equals("decimal") && value.equals(
            "integer"))) {
            return "decimal";
        }
        return "string";
    }

    private String infer(String value) {
        if (value.isEmpty()) {
            return "string";
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return "boolean";
        }
        if (value.matches("-?(0|[1-9][0-9]*)")) {
            try {
                Long.parseLong(value);
                return "integer";
            } catch (NumberFormatException outside) {
                return "string";
            }
        }
        if (value.matches("-?(0|[1-9][0-9]*)\\.[0-9]+")) {
            try {
                var decimal = new BigDecimal(value);
                if (decimal.precision() <= 65 && decimal.scale() <= 30 && decimal.precision() - decimal.scale() <= 35) {
                    return "decimal";
                }
            } catch (NumberFormatException invalid) {
                return "string";
            }
        }
        if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try {
                LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
                return "date";
            } catch (RuntimeException invalid) {
                return "string";
            }
        }
        if (value.contains("T")) {
            try {
                OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return "datetime";
            } catch (RuntimeException invalid) {
                return "string";
            }
        }
        if (value.startsWith("{")) {
            try {
                if (json.readTree(value).isObject()) {
                    return "object";
                }
            } catch (IOException invalid) {
                return "string";
            }
        }
        return "string";
    }
}
