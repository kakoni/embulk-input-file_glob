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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class CsvTokenizer {
    private CsvTokenizer(
            final Iterator<String> iterator,
            final char delimiterChar,
            final String delimiterFollowingString,
            final char quote,
            final char escape,
            final String newline,
            final boolean trimIfNotQuoted,
            final QuotesInQuotedFields quotesInQuotedFields,
            final long maxQuotedFieldLength,
            final String commentLineMarker,
            final String nullString) {
        this.delimiterChar = delimiterChar;
        this.delimiterFollowingString = delimiterFollowingString;
        this.quote = quote;
        this.escape = escape;
        this.newline = newline;
        this.trimIfNotQuoted = trimIfNotQuoted;
        this.quotesInQuotedFields = quotesInQuotedFields;
        this.maxQuotedFieldLength = maxQuotedFieldLength;
        this.commentLineMarker = commentLineMarker;
        this.nullString = nullString;

        this.quotedValueLines = new ArrayList<>();
        this.unreadLines = new ArrayDeque<>();
        this.iterator = iterator;

        this.recordState = RecordState.END;  // initial state is end of a record. nextRecord() must be called first
        this.lineNumber = 0;
        this.line = null;
        this.linePos = 0;
        this.wasQuotedColumn = false;
    }

    public static class Builder {
        private Builder(final String delimiter) {
            if (delimiter == null) {
                throw new NullPointerException("CsvTokenizer does not accept null as a delimiter.");
            }
            if (delimiter.isEmpty()) {
                throw new IllegalArgumentException("CsvTokenizer does not accept an empty delimiter.");
            }

            this.delimiterChar = delimiter.charAt(0);
            if (delimiter.length() > 1) {
                this.delimiterFollowingString = delimiter.substring(1);
            } else {
                this.delimiterFollowingString = null;
            }

            this.quote = '\"';
            this.escape = '\\';
            this.newline = "\r\n";
            this.trimIfNotQuoted = false;
            this.quotesInQuotedFields = QuotesInQuotedFields.ACCEPT_ONLY_RFC4180_ESCAPED;
            this.maxQuotedFieldLength = 131072L;  // 128KB
            this.commentLineMarker = null;
            this.nullString = null;
        }

        public Builder setQuote(final char quote) {
            this.quote = quote;
            return this;
        }

        public Builder noQuote() {
            this.quote = NO_QUOTE;
            return this;
        }

        public Builder setEscape(final char escape) {
            this.escape = escape;
            return this;
        }

        public Builder noEscape() {
            this.escape = NO_ESCAPE;
            return this;
        }

        public Builder setNewline(final String newline) {
            if (newline == null) {
                throw new NullPointerException("CsvTokenizer does not accept null as a newline.");
            }

            if ("\r\n".equals(newline) || "\r".equals(newline) || "\n".equals(newline)) {
                this.newline = newline;
                return this;
            }

            throw new IllegalArgumentException("CsvTokenizer does not accept \"" + escapeControl(newline) + "\" as a newline.");
        }

        public Builder enableTrimIfNotQuoted() {
            this.trimIfNotQuoted = true;
            return this;
        }

        public Builder acceptStrayQuotesAssumingNoDelimitersInFields() {
            this.quotesInQuotedFields = QuotesInQuotedFields.ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS;
            return this;
        }

        public Builder setMaxQuotedFieldLength(final long maxQuotedFieldLength) {
            this.maxQuotedFieldLength = maxQuotedFieldLength;
            return this;
        }

        public Builder setCommentLineMarker(final String commentLineMarker) {
            this.commentLineMarker = commentLineMarker;
            return this;
        }

        public Builder setNullString(final String nullString) {
            this.nullString = nullString;
            return this;
        }

        public CsvTokenizer build(final Iterator<String> iterator) {
            if (this.trimIfNotQuoted && this.quotesInQuotedFields != QuotesInQuotedFields.ACCEPT_ONLY_RFC4180_ESCAPED) {
                // The combination makes some syntax very ambiguous such as:
                //     val1,  \"\"val2\"\"  ,val3
                throw new IllegalStateException(
                        "[quotes_in_quoted_fields != ACCEPT_ONLY_RFC4180_ESCAPED] is not allowed to specify with [trim_if_not_quoted = true]");
            }
            return new CsvTokenizer(
                    iterator,
                    delimiterChar,
                    delimiterFollowingString,
                    quote,
                    escape,
                    newline,
                    trimIfNotQuoted,
                    quotesInQuotedFields,
                    maxQuotedFieldLength,
                    commentLineMarker,
                    nullString);
        }

        private final char delimiterChar;
        private final String delimiterFollowingString;

        private char quote;
        private char escape;
        private String newline;
        private boolean trimIfNotQuoted;
        private QuotesInQuotedFields quotesInQuotedFields;
        private long maxQuotedFieldLength;
        private String commentLineMarker;
        private String nullString;
    }

    public static Builder builder(final String delimiter) {
        return new Builder(delimiter);
    }

    public long getCurrentLineNumber() {
        return this.lineNumber;
    }

    public boolean skipHeaderLine() {
        if (!this.iterator.hasNext()) {
            return false;
        }
        this.iterator.next();
        this.lineNumber++;
        return true;
    }

    // returns skipped line
    public String skipCurrentLine() {
        final String skippedLine;
        if (this.quotedValueLines.isEmpty()) {
            skippedLine = this.line;
        } else {
            // recover lines of quoted value
            skippedLine = this.quotedValueLines.remove(0);  // TODO optimize performance
            this.unreadLines.addAll(this.quotedValueLines);
            this.lineNumber -= this.quotedValueLines.size();
            if (this.line != null) {
                this.unreadLines.add(this.line);
                this.lineNumber -= 1;
            }
            this.quotedValueLines.clear();
        }
        this.recordState = RecordState.END;
        return skippedLine;
    }

    // used by guess-csv
    public boolean nextRecord() {
        return this.nextRecord(true);
    }

    public boolean nextRecord(final boolean skipEmptyLine) {
        // If at the end of record, read the next line and initialize the state
        if (this.recordState != RecordState.END) {
            throw new RecordHasUnexpectedTrailingColumnException();
        }

        final boolean hasNext = this.nextLine(skipEmptyLine);
        if (hasNext) {
            this.recordState = RecordState.NOT_END;
            return true;
        } else {
            return false;
        }
    }

    private boolean nextLine(final boolean skipEmptyLine) {
        while (true) {
            if (!this.unreadLines.isEmpty()) {
                this.line = this.unreadLines.removeFirst();
            } else {
                if (!this.iterator.hasNext()) {
                    return false;
                }
                this.line = this.iterator.next();
            }
            this.linePos = 0;
            this.lineNumber++;

            final boolean skip =
                    skipEmptyLine && (this.line.isEmpty() || (this.commentLineMarker != null && this.line.startsWith(this.commentLineMarker)));
            if (!skip) {
                return true;
            }
        }
    }

    public boolean hasNextColumn() {
        return this.recordState == RecordState.NOT_END;
    }

    public String nextColumn() {
        if (!this.hasNextColumn()) {
            throw new RecordDoesNotHaveExpectedColumnException();
        }

        // reset last state
        this.wasQuotedColumn = false;
        this.quotedValueLines.clear();

        // local state
        int valueStartPos = this.linePos;
        int valueEndPos = 0;  // initialized by VALUE state and used by LAST_TRIM_OR_VALUE and
        StringBuilder quotedValue = null;  // initial by VALUE or FIRST_TRIM state and used by QUOTED_VALUE state
        ColumnState columnState = ColumnState.BEGIN;

        while (true) {
            final char c = nextChar();

            switch (columnState) {
                case BEGIN:
                    // TODO optimization: state is BEGIN only at the first character of a column.
                    //      this block can be out of the looop.
                    if (this.isDelimiter(c)) {
                        // empty value
                        if (this.delimiterFollowingString == null) {
                            return "";
                        } else if (this.isDelimiterFollowingFrom(this.linePos)) {
                            this.linePos += this.delimiterFollowingString.length();
                            return "";
                        }
                        // not a delimiter
                    }
                    if (this.isEndOfLine(c)) {
                        // empty value
                        this.recordState = RecordState.END;
                        return "";

                    } else if (this.isSpace(c) && this.trimIfNotQuoted) {
                        columnState = ColumnState.FIRST_TRIM;

                    } else if (this.isQuote(c)) {
                        valueStartPos = this.linePos;  // == 1
                        this.wasQuotedColumn = true;
                        quotedValue = new StringBuilder();
                        columnState = ColumnState.QUOTED_VALUE;

                    } else {
                        columnState = ColumnState.VALUE;
                    }
                    break;

                case FIRST_TRIM:
                    if (this.isDelimiter(c)) {
                        // empty value
                        if (this.delimiterFollowingString == null) {
                            return "";
                        } else if (this.isDelimiterFollowingFrom(this.linePos)) {
                            this.linePos += this.delimiterFollowingString.length();
                            return "";
                        }
                        // not a delimiter
                    }
                    if (this.isEndOfLine(c)) {
                        // empty value
                        this.recordState = RecordState.END;
                        return "";

                    } else if (this.isQuote(c)) {
                        // column has heading spaces and quoted. TODO should this be rejected?
                        valueStartPos = this.linePos;
                        this.wasQuotedColumn = true;
                        quotedValue = new StringBuilder();
                        columnState = ColumnState.QUOTED_VALUE;

                    } else if (this.isSpace(c)) {
                        // skip this character

                    } else {
                        valueStartPos = this.linePos - 1;
                        columnState = ColumnState.VALUE;
                    }
                    break;

                case VALUE:
                    if (this.isDelimiter(c)) {
                        if (this.delimiterFollowingString == null) {
                            return this.line.substring(valueStartPos, this.linePos - 1);
                        } else if (this.isDelimiterFollowingFrom(this.linePos)) {
                            final String value = this.line.substring(valueStartPos, this.linePos - 1);
                            this.linePos += this.delimiterFollowingString.length();
                            return value;
                        }
                        // not a delimiter
                    }
                    if (this.isEndOfLine(c)) {
                        this.recordState = RecordState.END;
                        return this.line.substring(valueStartPos, this.linePos);

                    } else if (this.isSpace(c) && this.trimIfNotQuoted) {
                        valueEndPos = this.linePos - 1;  // this is possibly end of value
                        columnState = ColumnState.LAST_TRIM_OR_VALUE;

                    // TODO not implemented yet foo""bar""baz -> [foo, bar, baz].append
                    //} else if (this.isQuote(c)) {
                    //    // In RFC4180, If fields are not enclosed with double quotes, then
                    //    // double quotes may not appear inside the fields. But they are often
                    //    // included in the fields. We should care about them later.

                    } else {
                        // keep VALUE state
                    }
                    break;

                case LAST_TRIM_OR_VALUE:
                    if (this.isDelimiter(c)) {
                        if (this.delimiterFollowingString == null) {
                            return this.line.substring(valueStartPos, valueEndPos);
                        } else if (this.isDelimiterFollowingFrom(this.linePos)) {
                            this.linePos += this.delimiterFollowingString.length();
                            return this.line.substring(valueStartPos, valueEndPos);
                        } else {
                            // not a delimiter
                        }
                    }
                    if (this.isEndOfLine(c)) {
                        this.recordState = RecordState.END;
                        return this.line.substring(valueStartPos, valueEndPos);

                    } else if (this.isSpace(c)) {
                        // keep LAST_TRIM_OR_VALUE state

                    } else {
                        // this spaces are not trailing spaces. go back to VALUE state
                        columnState = ColumnState.VALUE;
                    }
                    break;

                case QUOTED_VALUE:
                    if (this.isEndOfLine(c)) {
                        // multi-line quoted value
                        quotedValue.append(this.line.substring(valueStartPos, this.linePos));
                        quotedValue.append(this.newline);
                        this.quotedValueLines.add(this.line);
                        if (!this.nextLine(false)) {
                            throw new EndOfFileInQuotedFieldException();
                        }
                        valueStartPos = 0;

                    } else if (this.isQuote(c)) {
                        final char next = this.peekNextChar();
                        final char nextNext = this.peekNextNextChar();
                        if (this.isQuote(next)
                                && (this.quotesInQuotedFields != QuotesInQuotedFields.ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS
                                        || (!this.isDelimiter(nextNext) && !this.isEndOfLine(nextNext)))) {
                            // Escaped by preceding it with another quote.
                            // A quote just before a delimiter or an end of line is recognized as a functional quote,
                            // not just as a non-escaped stray "quote character" included the field, even if
                            // ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS is specified.
                            quotedValue.append(this.line.substring(valueStartPos, this.linePos));
                            valueStartPos = ++this.linePos;
                        } else if (this.quotesInQuotedFields == QuotesInQuotedFields.ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS
                                && !(this.isDelimiter(next) || this.isEndOfLine(next))) {
                            // A non-escaped stray "quote character" in the field is processed as a regular character
                            // if ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS is specified,
                            if ((this.linePos - valueStartPos) + quotedValue.length() > this.maxQuotedFieldLength) {
                                throw new QuotedFieldLengthLimitExceededException(this.maxQuotedFieldLength);
                            }
                        } else {
                            quotedValue.append(this.line.substring(valueStartPos, this.linePos - 1));
                            columnState = ColumnState.AFTER_QUOTED_VALUE;
                        }

                    } else if (this.isEscape(c)) {  // isQuote must be checked first in case of this.quote == this.escape
                        // In RFC 4180, CSV's escape char is '\"'. But '\\' is often used.
                        final char next = this.peekNextChar();
                        if (this.isEndOfLine(c)) {
                            // escape end of line. TODO assuming multi-line quoted value without newline?
                            quotedValue.append(this.line.substring(valueStartPos, this.linePos));
                            this.quotedValueLines.add(this.line);
                            if (!this.nextLine(false)) {
                                throw new EndOfFileInQuotedFieldException();
                            }
                            valueStartPos = 0;
                        } else if (this.isQuote(next) || this.isEscape(next)) { // escaped quote
                            quotedValue.append(this.line.substring(valueStartPos, this.linePos - 1));
                            quotedValue.append(next);
                            valueStartPos = ++this.linePos;
                        }

                    } else {
                        if ((this.linePos - valueStartPos) + quotedValue.length() > this.maxQuotedFieldLength) {
                            throw new QuotedFieldLengthLimitExceededException(this.maxQuotedFieldLength);
                        }
                        // keep QUOTED_VALUE state
                    }
                    break;

                case AFTER_QUOTED_VALUE:
                    if (this.isDelimiter(c)) {
                        if (this.delimiterFollowingString == null) {
                            return quotedValue.toString();
                        } else if (this.isDelimiterFollowingFrom(this.linePos)) {
                            this.linePos += this.delimiterFollowingString.length();
                            return quotedValue.toString();
                        }
                        // not a delimiter
                    }
                    if (this.isEndOfLine(c)) {
                        this.recordState = RecordState.END;
                        return quotedValue.toString();

                    } else if (this.isSpace(c)) {
                        // column has trailing spaces and quoted. TODO should this be rejected?

                    } else {
                        throw new InvalidCharacterAfterQuoteException(c, this.quote);
                    }
                    break;

                default:
                    assert false;
            }
        }
    }

    public String nextColumnOrNull() {
        final String v = this.nextColumn();
        if (this.nullString == null) {
            if (v.isEmpty()) {
                if (this.wasQuotedColumn) {
                    return "";
                } else {
                    return null;
                }
            } else {
                return v;
            }
        } else {
            if (v.equals(this.nullString)) {
                return null;
            } else {
                return v;
            }
        }
    }

    public boolean wasQuotedColumn() {
        return this.wasQuotedColumn;
    }

    private char nextChar() {
        if (this.line == null) {
            throw new IllegalStateException("nextColumn is called after end of file");
        }

        if (this.linePos >= this.line.length()) {
            return END_OF_LINE;
        } else {
            return this.line.charAt(this.linePos++);
        }
    }

    private char peekNextChar() {
        if (this.line == null) {
            throw new IllegalStateException("peekNextChar is called after end of file");
        }

        if (this.linePos >= this.line.length()) {
            return END_OF_LINE;
        } else {
            return this.line.charAt(this.linePos);
        }
    }

    private char peekNextNextChar() {
        if (this.line == null) {
            throw new IllegalStateException("peekNextNextChar is called after end of file");
        }

        if (this.linePos + 1 >= this.line.length()) {
            return END_OF_LINE;
        } else {
            return this.line.charAt(this.linePos + 1);
        }
    }

    private boolean isSpace(final char c) {
        return c == ' ';
    }

    private boolean isDelimiterFollowingFrom(final int pos) {
        if (this.line.length() < pos + this.delimiterFollowingString.length()) {
            return false;
        }
        for (int i = 0; i < this.delimiterFollowingString.length(); i++) {
            if (this.delimiterFollowingString.charAt(i) != this.line.charAt(pos + i)) {
                return false;
            }
        }
        return true;
    }

    private boolean isDelimiter(final char c) {
        return c == this.delimiterChar;
    }

    private boolean isEndOfLine(final char c) {
        return c == END_OF_LINE;
    }

    private boolean isQuote(final char c) {
        return this.quote != NO_QUOTE && c == this.quote;
    }

    private boolean isEscape(final char c) {
        return this.escape != NO_ESCAPE && c == this.escape;
    }

    private static enum RecordState {
        NOT_END, END,
    }

    private static enum ColumnState {
        BEGIN, VALUE, QUOTED_VALUE, AFTER_QUOTED_VALUE, FIRST_TRIM, LAST_TRIM_OR_VALUE,
    }

    private static enum QuotesInQuotedFields {
        ACCEPT_ONLY_RFC4180_ESCAPED,
        ACCEPT_STRAY_QUOTES_ASSUMING_NO_DELIMITERS_IN_FIELDS,
        ;
    }

    private static String escapeControl(final String from) {
        return from.chars().mapToObj(c -> {
            if (c > 0x20) {
                return "" + (char) c;
            }

            switch (c) {
                case '\b':
                    return "\\b";
                case '\n':
                    return "\\n";
                case '\t':
                    return "\\t";
                case '\f':
                    return "\\f";
                case '\r':
                    return "\\r";
                default:
                    return String.format("\\u%04x", c);
            }
        }).collect(Collectors.joining());
    }

    static final char NO_QUOTE = '\0';
    static final char NO_ESCAPE = '\0';

    private static final char END_OF_LINE = '\0';

    private final Iterator<String> iterator;

    private final char delimiterChar;
    private final String delimiterFollowingString;
    private final char quote;
    private final char escape;
    private final String newline;
    private final boolean trimIfNotQuoted;
    private final QuotesInQuotedFields quotesInQuotedFields;
    private final long maxQuotedFieldLength;
    private final String commentLineMarker;
    private final String nullString;

    private final List<String> quotedValueLines;
    private final Deque<String> unreadLines;

    private RecordState recordState;
    private long lineNumber;
    private String line;
    private int linePos;
    private boolean wasQuotedColumn;
}
