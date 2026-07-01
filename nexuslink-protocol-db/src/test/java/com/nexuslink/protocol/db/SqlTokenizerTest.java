package com.nexuslink.protocol.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SqlTokenizerTest {

    private static List<SqlTokenType> types(String sql) {
        return SqlTokenizer.tokenize(sql).stream().map(SqlToken::type).collect(Collectors.toList());
    }

    /** Types of the non-whitespace tokens only, to make sequence assertions readable. */
    private static List<SqlTokenType> significantTypes(String sql) {
        return SqlTokenizer.tokenize(sql).stream()
                .filter(t -> t.type() != SqlTokenType.WHITESPACE)
                .map(SqlToken::type)
                .collect(Collectors.toList());
    }

    private static String reassemble(String sql) {
        return SqlTokenizer.tokenize(sql).stream().map(SqlToken::text).collect(Collectors.joining());
    }

    @Test
    void nullAndEmptyYieldNoTokens() {
        assertTrue(SqlTokenizer.tokenize(null).isEmpty());
        assertTrue(SqlTokenizer.tokenize("").isEmpty());
    }

    @Test
    void basicSelectHasExpectedTypeSequence() {
        List<SqlTokenType> t = significantTypes("SELECT a, b FROM t WHERE x = 1");
        assertEquals(List.of(
                SqlTokenType.KEYWORD,     // SELECT
                SqlTokenType.IDENTIFIER,  // a
                SqlTokenType.OPERATOR,    // ,
                SqlTokenType.IDENTIFIER,  // b
                SqlTokenType.KEYWORD,     // FROM
                SqlTokenType.IDENTIFIER,  // t
                SqlTokenType.KEYWORD,     // WHERE
                SqlTokenType.IDENTIFIER,  // x
                SqlTokenType.OPERATOR,    // =
                SqlTokenType.NUMBER       // 1
        ), t);
    }

    @Test
    void whitespaceIsTokenizedAndPreserved() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("a  b");
        assertEquals(3, tokens.size());
        assertEquals(SqlTokenType.IDENTIFIER, tokens.get(0).type());
        assertEquals(SqlTokenType.WHITESPACE, tokens.get(1).type());
        assertEquals("  ", tokens.get(1).text());
        assertEquals(SqlTokenType.IDENTIFIER, tokens.get(2).type());
    }

    @Test
    void singleQuotedStringWithEscape() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("'it''s'");
        assertEquals(1, tokens.size());
        assertEquals(SqlTokenType.STRING, tokens.get(0).type());
        assertEquals("'it''s'", tokens.get(0).text());
    }

    @Test
    void stringWithEscapeInContext() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("x = 'a''b'");
        assertEquals(SqlTokenType.STRING, tokens.get(tokens.size() - 1).type());
        assertEquals("'a''b'", tokens.get(tokens.size() - 1).text());
    }

    @Test
    void doubleQuotedIdentifierWithSpaces() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("\"my col\"");
        assertEquals(1, tokens.size());
        assertEquals(SqlTokenType.QUOTED_IDENTIFIER, tokens.get(0).type());
        assertEquals("\"my col\"", tokens.get(0).text());
    }

    @Test
    void backtickQuotedIdentifier() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("`weird name`");
        assertEquals(1, tokens.size());
        assertEquals(SqlTokenType.QUOTED_IDENTIFIER, tokens.get(0).type());
        assertEquals("`weird name`", tokens.get(0).text());
    }

    @Test
    void quotedIdentifierWithDoubledEscape() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("\"a\"\"b\"");
        assertEquals(1, tokens.size());
        assertEquals(SqlTokenType.QUOTED_IDENTIFIER, tokens.get(0).type());
        assertEquals("\"a\"\"b\"", tokens.get(0).text());
    }

    @Test
    void lineComment() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT 1 -- hi\nFROM t");
        boolean hasLineComment = tokens.stream()
                .anyMatch(t -> t.type() == SqlTokenType.LINE_COMMENT && t.text().equals("-- hi"));
        assertTrue(hasLineComment);
        // The newline after the comment is separate whitespace, so round-trip still holds.
        assertEquals("SELECT 1 -- hi\nFROM t", reassemble("SELECT 1 -- hi\nFROM t"));
    }

    @Test
    void hashLineComment() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("# comment");
        assertEquals(1, tokens.size());
        assertEquals(SqlTokenType.LINE_COMMENT, tokens.get(0).type());
    }

    @Test
    void blockComment() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("/* a\nb */");
        assertEquals(1, tokens.size());
        assertEquals(SqlTokenType.BLOCK_COMMENT, tokens.get(0).type());
        assertEquals("/* a\nb */", tokens.get(0).text());
    }

    @Test
    void decimalAndScientificNumbers() {
        assertEquals(SqlTokenType.NUMBER, SqlTokenizer.tokenize("3.14").get(0).type());
        assertEquals("3.14", SqlTokenizer.tokenize("3.14").get(0).text());

        List<SqlToken> sci = SqlTokenizer.tokenize("1.0e-3");
        assertEquals(1, sci.size());
        assertEquals(SqlTokenType.NUMBER, sci.get(0).type());
        assertEquals("1.0e-3", sci.get(0).text());

        List<SqlToken> leadingDot = SqlTokenizer.tokenize(".5");
        assertEquals(SqlTokenType.NUMBER, leadingDot.get(0).type());
        assertEquals(".5", leadingDot.get(0).text());

        List<SqlToken> exp = SqlTokenizer.tokenize("2E10");
        assertEquals(SqlTokenType.NUMBER, exp.get(0).type());
        assertEquals("2E10", exp.get(0).text());
    }

    @Test
    void memberAccessIsNotSwallowedByNumber() {
        // "t.col" -> identifier, operator '.', identifier (no number)
        assertEquals(List.of(
                SqlTokenType.IDENTIFIER, SqlTokenType.OPERATOR, SqlTokenType.IDENTIFIER),
                significantTypes("t.col"));
    }

    @Test
    void integerFollowedByDotThenKeyword() {
        // "1." with nothing numeric after the dot: number '1', operator '.'
        List<SqlToken> tokens = SqlTokenizer.tokenize("1.");
        assertEquals(2, tokens.size());
        assertEquals(SqlTokenType.NUMBER, tokens.get(0).type());
        assertEquals("1", tokens.get(0).text());
        assertEquals(SqlTokenType.OPERATOR, tokens.get(1).type());
        assertEquals(".", tokens.get(1).text());
    }

    @Test
    void parameters() {
        assertEquals(SqlTokenType.PARAMETER, SqlTokenizer.tokenize("?").get(0).type());

        List<SqlToken> named = SqlTokenizer.tokenize(":name");
        assertEquals(1, named.size());
        assertEquals(SqlTokenType.PARAMETER, named.get(0).type());
        assertEquals(":name", named.get(0).text());

        List<SqlToken> positional = SqlTokenizer.tokenize("$1");
        assertEquals(1, positional.size());
        assertEquals(SqlTokenType.PARAMETER, positional.get(0).type());
        assertEquals("$1", positional.get(0).text());
    }

    @Test
    void parametersInContext() {
        assertEquals(List.of(
                SqlTokenType.KEYWORD,     // WHERE
                SqlTokenType.IDENTIFIER,  // id
                SqlTokenType.OPERATOR,    // =
                SqlTokenType.PARAMETER,   // :id
                SqlTokenType.KEYWORD,     // AND
                SqlTokenType.IDENTIFIER,  // n
                SqlTokenType.OPERATOR,    // =
                SqlTokenType.PARAMETER    // ?
        ), significantTypes("WHERE id = :id AND n = ?"));
    }

    @Test
    void colonNotFollowedByNameIsOperator() {
        // "::" is a Postgres cast operator, not a parameter.
        List<SqlToken> tokens = SqlTokenizer.tokenize("a::int");
        assertEquals(SqlTokenType.OPERATOR, tokens.get(1).type());
        assertEquals("::", tokens.get(1).text());
    }

    @Test
    void keywordDetectionIsCaseInsensitive() {
        assertEquals(SqlTokenType.KEYWORD, SqlTokenizer.tokenize("select").get(0).type());
        assertEquals(SqlTokenType.KEYWORD, SqlTokenizer.tokenize("Select").get(0).type());
        assertEquals(SqlTokenType.KEYWORD, SqlTokenizer.tokenize("SELECT").get(0).type());
        assertTrue(SqlTokenizer.isKeyword("where"));
        assertTrue(SqlTokenizer.isKeyword("WHERE"));
        assertFalse(SqlTokenizer.isKeyword("wherever"));
        assertFalse(SqlTokenizer.isKeyword(null));
    }

    @Test
    void identifierContainingKeywordSubstringStaysIdentifier() {
        assertEquals(SqlTokenType.IDENTIFIER, SqlTokenizer.tokenize("selected").get(0).type());
        assertEquals(SqlTokenType.IDENTIFIER, SqlTokenizer.tokenize("from_date").get(0).type());
        assertEquals(SqlTokenType.IDENTIFIER, SqlTokenizer.tokenize("ordering").get(0).type());
    }

    @Test
    void keywordSetIsExposedAndUnmodifiable() {
        assertTrue(SqlTokenizer.keywords().contains("SELECT"));
        assertTrue(SqlTokenizer.keywords().contains("JOIN"));
        assertThrows(UnsupportedOperationException.class, () -> SqlTokenizer.keywords().add("FOO"));
    }

    @Test
    void multiCharOperators() {
        assertEquals("<=", SqlTokenizer.tokenize("a <= b").stream()
                .filter(t -> t.type() == SqlTokenType.OPERATOR).findFirst().get().text());
        assertEquals("<>", SqlTokenizer.tokenize("a<>b").get(1).text());
        assertEquals("||", SqlTokenizer.tokenize("a||b").get(1).text());
        assertEquals(">=", SqlTokenizer.tokenize("a>=b").get(1).text());
    }

    @Test
    void spansAreContiguousAndConsistent() {
        String sql = "SELECT a, b FROM t WHERE x = 1";
        List<SqlToken> tokens = SqlTokenizer.tokenize(sql);
        int expected = 0;
        for (SqlToken tok : tokens) {
            assertEquals(expected, tok.start());
            assertEquals(sql.substring(tok.start(), tok.end()), tok.text());
            assertEquals(tok.text().length(), tok.length());
            expected = tok.end();
        }
        assertEquals(sql.length(), expected);
    }

    @Test
    void roundTripForVariousScripts() {
        String[] scripts = {
                "SELECT a, b FROM t WHERE x = 1",
                "UPDATE users SET name = 'O''Brien' WHERE id = :id;",
                "-- a comment\nSELECT /* inline */ COUNT(*) FROM \"my table\" WHERE v >= 3.14e2",
                "INSERT INTO t (a,b) VALUES ($1, ?);\n# trailing mysql comment",
                "SELECT `col`, .5, 2E10 FROM t",
                "   \t\n  ",
                "gibberish @#% ~^ 你好 end"
        };
        for (String s : scripts) {
            assertEquals(s, reassemble(s), "round-trip failed for: " + s);
        }
    }

    @Test
    void unterminatedStringRunsToEnd() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT 'abc");
        SqlToken last = tokens.get(tokens.size() - 1);
        assertEquals(SqlTokenType.STRING, last.type());
        assertEquals("'abc", last.text());
        assertEquals("SELECT 'abc", reassemble("SELECT 'abc"));
    }

    @Test
    void unterminatedBlockCommentRunsToEnd() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT /* abc");
        SqlToken last = tokens.get(tokens.size() - 1);
        assertEquals(SqlTokenType.BLOCK_COMMENT, last.type());
        assertEquals("/* abc", last.text());
    }

    @Test
    void unterminatedQuotedIdentifierRunsToEnd() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("\"abc");
        assertEquals(1, tokens.size());
        assertEquals(SqlTokenType.QUOTED_IDENTIFIER, tokens.get(0).type());
        assertEquals("\"abc", tokens.get(0).text());
    }

    @Test
    void unknownCharactersAreCoveredNotDropped() {
        List<SqlToken> tokens = SqlTokenizer.tokenize("你");
        assertEquals(1, tokens.size());
        // A letter (even non-ASCII) is treated as an identifier start.
        assertEquals(SqlTokenType.IDENTIFIER, tokens.get(0).type());
    }
}
