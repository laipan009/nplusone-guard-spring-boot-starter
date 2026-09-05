package io.github.laipan009.nplusone.core;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeStatementInspectorTest {

    @Test
    void whenChained_shouldGiveDetectorTheOriginalSqlAndReturnTheDelegateRewrite() {
        var seen = new ArrayList<String>();
        StatementInspector recorder = sql -> {
            seen.add(sql);
            return null;
        };
        StatementInspector rewriter = sql -> "/* traced */ " + sql;
        var composite = new CompositeStatementInspector(List.of(recorder, rewriter));

        var result = composite.inspect("select 1");

        assertThat(seen).containsExactly("select 1");
        assertThat(result).isEqualTo("/* traced */ select 1");
    }
}
