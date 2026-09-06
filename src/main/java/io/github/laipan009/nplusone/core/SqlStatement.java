package io.github.laipan009.nplusone.core;

import java.util.List;
import java.util.regex.Pattern;

/** Classifies Hibernate SELECTs without changing the SQL used for counting, allowlisting or execution. */
final class SqlStatement {

    private static final String SELECT = "select";
    private static final String IDENTIFIER = "(?:[\\p{L}_][\\p{L}\\p{N}_$]*"
            + "|\"(?:[^\"]|\"\")+\"|`(?:[^`]|``)+`|\\[(?:[^\\]]|\\]\\])+\\])";
    private static final String QUALIFIED_NAME = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")*";
    private static final List<Pattern> SEQUENCE_VALUES = List.of(
            sequencePattern("next\\s+value\\s+for\\s+" + QUALIFIED_NAME),
            sequencePattern("nextval\\s*\\(\\s*(?:'[^']*(?:''[^']*)*'|" + QUALIFIED_NAME + ")\\s*\\)"),
            sequencePattern(QUALIFIED_NAME + "\\s*\\.\\s*nextval(?:\\s+from\\s+(?:dual|sys\\.dummy))?"),
            sequencePattern("nextval\\s+for\\s+" + QUALIFIED_NAME + "\\s+from\\s+sysibm\\.sysdummy1"));

    private SqlStatement() {
    }

    static boolean isCountedSelect(String sql) {
        var statement = withoutLeadingComments(sql);
        if (!statement.regionMatches(true, 0, SELECT, 0, SELECT.length())
                || statement.length() == SELECT.length()
                || Character.isJavaIdentifierPart(statement.charAt(SELECT.length()))) {
            return false;
        }
        var projection = withoutLeadingComments(statement.substring(SELECT.length()));
        return SEQUENCE_VALUES.stream().noneMatch(pattern -> pattern.matcher(projection).matches());
    }

    private static Pattern sequencePattern(String expression) {
        return Pattern.compile(expression + "\\s*;?\\s*", Pattern.CASE_INSENSITIVE);
    }

    private static String withoutLeadingComments(String sql) {
        var remaining = sql.stripLeading();
        while (remaining.startsWith("/*") || remaining.startsWith("--")) {
            remaining = remaining.substring(commentEnd(remaining)).stripLeading();
        }
        return remaining;
    }

    private static int commentEnd(String sql) {
        if (sql.startsWith("/*")) {
            var end = sql.indexOf("*/", 2);
            return end < 0 ? sql.length() : end + 2;
        }
        var end = 2;
        while (end < sql.length() && sql.charAt(end) != '\n' && sql.charAt(end) != '\r') {
            end++;
        }
        return end;
    }
}
