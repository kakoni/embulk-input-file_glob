/*
 * Copyright 2014 The Embulk project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.embulk.parser.csv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.embulk.spi.Buffer;
import org.embulk.spi.BufferImpl;
import org.embulk.spi.FileInput;
import org.embulk.util.file.ListFileInput;
import org.embulk.util.text.LineDecoder;
import org.junit.Test;

public class TestCsvTokenizer {
    private static CsvTokenizer.Builder initialBuilder(final String delimiter) {
        return CsvTokenizer.builder(delimiter).setNewline("\n");
    }

    private static CsvTokenizer.Builder initialBuilder() {
        return initialBuilder(",");
    }

    private static FileInput newFileInputFromLines(final String newline, final String... lines) {
        List<Buffer> buffers = new ArrayList<>();
        for (String line : lines) {
            byte[] buffer = (line + newline).getBytes(StandardCharsets.UTF_8);
            buffers.add(BufferImpl.wrap(buffer));
        }
        return new ListFileInput(Arrays.asList(buffers));
    }

    private static FileInput newFileInputFromText(final String newline, final String text) {
        return new ListFileInput(
                Arrays.asList(Arrays.asList(
                        BufferImpl.wrap(text.getBytes(StandardCharsets.UTF_8)))));
    }

    private static List<List<String>> parse(final CsvTokenizer.Builder builder, final String newline, final int columns, final String... lines) {
        return parse(builder, columns, newFileInputFromLines(newline, lines));
    }

    private static List<List<String>> parse(final CsvTokenizer.Builder builder, final int columns, final FileInput input) {
        LineDecoder decoder = LineDecoder.of(input, StandardCharsets.UTF_8, null);
        decoder.nextFile();
        final CsvTokenizer tokenizer = builder.build(decoder.iterator());

        List<List<String>> records = new ArrayList<>();
        while (tokenizer.nextRecord()) {
            List<String> record = new ArrayList<>();
            for (int i = 0; i < columns; i++) {
                String v = tokenizer.nextColumnOrNull();
                record.add(v);
            }
            records.add(record);
        }
        return records;
    }

    private List<List<String>> expectedRecords(int columnCount, String... values) {
        List<List<String>> records = new ArrayList<>();
        List<String> columns = null;
        for (int i = 0; i < values.length; i++) {
            if (i % columnCount == 0) {
                columns = new ArrayList<String>();
                records.add(columns);
            }
            columns.add(values[i]);
        }
        return records;
    }

    @Test
    public void testSimple() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(2,
                    "aaa", "bbb",
                    "ccc", "ddd"),
                parse(builder, "\n", 2,
                    "aaa,bbb",
                    "ccc,ddd"));
    }

    @Test
    public void testSkipEmptyLine() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(2,
                    "aaa", "bbb",
                    "ccc", "ddd"),
                parse(builder, "\n", 2,
                    "", "aaa,bbb", "", "",
                    "ccc,ddd", "", ""));
    }

    @Test
    public void parseEmptyColumnsToNull() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(2,
                    null, null,
                    "", "",
                    "  ", "  "), // not trimmed
                parse(builder, "\n", 2,
                    ",",
                    "\"\",\"\"",
                    "  ,  "));
    }

    @Test
    public void parseEmptyColumnsToNullTrimmed() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        builder.enableTrimIfNotQuoted();
        assertEquals(
                expectedRecords(2,
                    null, null,
                    "", "",
                    null, null),  // trimmed
                parse(builder, "\n", 2,
                    ",",
                    "\"\",\"\"",
                    "  ,  "));
    }

    @Test
    public void testMultilineQuotedValueWithEmptyLine() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(2,
                    "a", "\nb\n\n",
                    "c", "d"),
                parse(builder, "\n", 2,
                    "",
                    "a,\"", "b", "", "\"",
                    "c,d"));
    }

    @Test
    public void testEndOfFileWithoutNewline() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        // In RFC 4180, the last record in the file may or may not have
        // an ending line break.
        assertEquals(expectedRecords(2,
                        "aaa", "bbb",
                        "ccc", "ddd"),
                parse(builder, 2, newFileInputFromText("\n",
                        "aaa,bbb\nccc,ddd")));
    }

    @Test
    public void testChangeDelimiter() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder("\t");  // TSV format
        assertEquals(expectedRecords(2,
                        "aaa", "bbb",
                        "ccc", "ddd"),
                parse(builder, "\n", 2,
                        "aaa\tbbb",
                        "ccc\tddd"));
    }

    @Test
    public void testDefaultNullString() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(2,
                        null, "",
                        "NULL", "NULL"),
                parse(builder, "\n", 2,
                        ",\"\"",
                        "NULL,\"NULL\""));
    }

    @Test
    public void testChangeNullString() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        builder.setNullString("NULL");
        assertEquals(expectedRecords(2,
                        "", "",
                        null, null),
                parse(builder, "\n", 2,
                        ",\"\"",
                        "NULL,\"NULL\""));
    }

    @Test
    public void testQuotedValues() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(2,
                        "a\na\na", "b,bb",
                        "cc\"c", "\"ddd",
                        null, ""),
                parse(builder, 2, newFileInputFromText("\r\n",
                        "\n\"a\na\na\",\"b,bb\"\n\n\"cc\"\"c\",\"\"\"ddd\"\n,\"\"\n")));
    }

    @Test
    public void parseEscapedValues() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(2,
                        "a\"aa", "b,bb\"",
                        "cc\"c", "\"ddd",
                        null, ""),
                parse(builder, 2, newFileInputFromText("\r\n",
                    "\n\"a\\\"aa\",\"b,bb\\\"\"\n\n\"cc\"\"c\",\"\"\"ddd\"\n,\"\"\n")));
    }

    @Test
    public void testCommentLineMarker() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        builder.setCommentLineMarker("#");
        assertEquals(expectedRecords(2,
                        "aaa", "bbb",
                        "eee", "fff"),
                parse(builder, "\n", 2,
                        "aaa,bbb",
                        "#ccc,ddd",
                        "eee,fff"));
    }

    @Test
    public void trimNonQuotedValues() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(2,
                    "  aaa  ", "  b cd ",
                    "  ccc","dd d \n "), // quoted values are not changed
                parse(builder, 2, newFileInputFromText("\n",
                        "  aaa  ,  b cd \n\"  ccc\",\"dd d \n \"")));

        final CsvTokenizer.Builder builder2 = initialBuilder();
        // trim_if_not_quoted is true
        builder2.enableTrimIfNotQuoted();
        assertEquals(expectedRecords(2,
                    "aaa", "b cd",
                    "  ccc","dd d \n "), // quoted values are not changed
                parse(builder2, 2, newFileInputFromText("\n",
                        "  aaa  ,  b cd \n\"  ccc\",\"dd d \n \"")));
    }

    @Test
    public void parseQuotedValueWithSpacesAndTrimmingOption() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        builder.enableTrimIfNotQuoted();
        assertEquals(expectedRecords(2,
                        "heading1", "heading2",
                        "trailing1","trailing2",
                        "trailing\n3","trailing\n4"),
                parse(builder, "\n", 2,
                    "  \"heading1\",  \"heading2\"",
                    "\"trailing1\"  ,\"trailing2\"  ",
                    "\"trailing\n3\"  ,\"trailing\n4\"  "));
    }


    @Test
    public void parseWithDefaultQuotesInQuotedFields() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(
                         2,
                         "foo\"bar", "foofoo\"barbar",
                         "baz\"\"qux", "bazbaz\"\"quxqux"),
                     parse(
                         builder, "\n", 2,
                         "\"foo\"\"bar\",\"foofoo\"\"barbar\"",
                         "\"baz\"\"\"\"qux\",\"bazbaz\"\"\"\"quxqux\""));
    }

    @SuppressWarnings("checkstyle:AbbreviationAsWordInName")
    @Test
    public void parseWithQuotesInQuotedFields_ACCEPT_ONLY_RFC4180_ESCAPED() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        assertEquals(expectedRecords(
                         2,
                         "foo\"bar", "foofoo\"barbar",
                         "baz\"\"qux", "bazbaz\"\"quxqux"),
                     parse(
                         builder, "\n", 2,
                         "\"foo\"\"bar\",\"foofoo\"\"barbar\"",
                         "\"baz\"\"\"\"qux\",\"bazbaz\"\"\"\"quxqux\""));
    }

    @Test
    public void throwWithDefaultQuotesInQuotedFields() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        try {
            parse(builder, "\n", 2, "\"foo\"bar\",\"hoge\"fuga\"");
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof InvalidCsvQuotationException);
            assertEquals("Unexpected extra character 'b' after a value quoted by '\"'", e.getMessage());
            return;
        }
    }

    @SuppressWarnings("checkstyle:AbbreviationAsWordInName")
    @Test
    public void throwWithQuotesInQuotedFields_ACCEPT_ONLY_RFC4180_ESCAPED() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        try {
            parse(builder, "\n", 2, "\"foo\"bar\",\"hoge\"fuga\"");
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof InvalidCsvQuotationException);
            assertEquals("Unexpected extra character 'b' after a value quoted by '\"'", e.getMessage());
            return;
        }
    }

    @SuppressWarnings("checkstyle:AbbreviationAsWordInName")
    @Test
    public void parseWithQuotesInQuotedFields_ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        builder.acceptStrayQuotesAssumingNoDelimitersInFields();
        assertEquals(expectedRecords(
                         2,
                         "foo\"bar", "foofoo\"barbar",
                         "baz\"\"qux", "bazbaz\"\"quxqux",
                         "\"embulk\"", "\"embul\"\"k\""),
                     parse(
                         builder, "\n", 2,
                         "\"foo\"bar\",\"foofoo\"\"barbar\"",
                         "\"baz\"\"\"qux\",\"bazbaz\"\"\"\"quxqux\"",
                         "\"\"\"embulk\"\",\"\"embul\"\"\"k\"\""));
    }

    @Test
    public void throwQuotedSizeLimitExceededException() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        builder.setMaxQuotedFieldLength(8);

        try {
            parse(builder, "\n", 2,
                    "v1,v2",
                    "v3,\"0123456789\"");
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof QuotedSizeLimitExceededException);
        }

        // multi-line
        try {
            parse(builder, "\n", 2,
                    "v1,v2",
                    "\"012345\n6789\",v3");
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof QuotedSizeLimitExceededException);
        }
    }

    @Test
    public void recoverFromQuotedSizeLimitExceededException() throws Exception {
        final CsvTokenizer.Builder builder = initialBuilder();
        builder.setMaxQuotedFieldLength(12);

        String[] lines = new String[] {
            "v1,v2",
            "v3,\"0123",  // this is a broken line and should be skipped
            "v4,v5",      // this line should be not be skiped
            "v6,v7",      // this line should be not be skiped
        };
        final FileInput input = newFileInputFromLines("\n", lines);
        final LineDecoder decoder = LineDecoder.of(input, StandardCharsets.UTF_8, null);
        decoder.nextFile();
        final CsvTokenizer tokenizer = builder.build(decoder.iterator());

        assertTrue(tokenizer.nextRecord());
        assertEquals("v1", tokenizer.nextColumn());
        assertEquals("v2", tokenizer.nextColumn());

        assertTrue(tokenizer.nextRecord());
        assertEquals("v3", tokenizer.nextColumn());
        try {
            tokenizer.nextColumn();
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof QuotedSizeLimitExceededException);
        }
        assertEquals("v3,\"0123", tokenizer.skipCurrentLine());

        assertTrue(tokenizer.nextRecord());
        assertEquals("v4", tokenizer.nextColumn());
        assertEquals("v5", tokenizer.nextColumn());

        assertTrue(tokenizer.nextRecord());
        assertEquals("v6", tokenizer.nextColumn());
        assertEquals("v7", tokenizer.nextColumn());
    }

    /*
    @Test
    public void parseEscapedQuotedValues() throws Exception {
        List<List<String>> parsed = doParse(task, bufferList("utf-8",
                "\"aa,a\",\",aaa\",\"aaa,\"", "\n",
                "\"bb\"\"b\",\"\"\"bbb\",\"bbb\"\"\"", "\n",
                "\"cc\\\"c\",\"\\\"ccc\",\"ccc\\\"\"", "\n",
                "\"dd\nd\",\"\nddd\",\"ddd\n\"", "\n"));
        assertEquals(Arrays.asList(
                        Arrays.asList("aa,a", ",aaa", "aaa,"),
                        Arrays.asList("bb\"b", "\"bbb", "bbb\""),
                        Arrays.asList("cc\"c", "\"ccc", "ccc\""),
                        Arrays.asList("dd\nd", "\nddd", "ddd\n")),
                parsed);
    }
    */
}
