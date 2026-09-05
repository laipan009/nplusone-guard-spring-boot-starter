package io.github.laipan009.nplusone.core;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;

/**
 * Runs the detector first, on the SQL exactly as Hibernate generated it, then the inspector the application had
 * configured before the starter, whose rewritten text is what reaches JDBC.
 */
public record CompositeStatementInspector(List<StatementInspector> inspectors) implements StatementInspector {

    public CompositeStatementInspector {
        inspectors = List.copyOf(inspectors);
    }

    @Override
    public String inspect(String sql) {
        var current = sql;
        for (var inspector : inspectors) {
            var result = inspector.inspect(current);
            if (result != null) {
                current = result;
            }
        }
        return current;
    }
}
