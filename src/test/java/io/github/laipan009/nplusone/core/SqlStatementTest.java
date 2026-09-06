package io.github.laipan009.nplusone.core;

import org.hibernate.community.dialect.sequence.AltibaseSequenceSupport;
import org.hibernate.community.dialect.sequence.FirebirdSequenceSupport;
import org.hibernate.community.dialect.sequence.InformixSequenceSupport;
import org.hibernate.community.dialect.sequence.IngresLegacySequenceSupport;
import org.hibernate.community.dialect.sequence.MaxDBSequenceSupport;
import org.hibernate.community.dialect.sequence.MimerSequenceSupport;
import org.hibernate.community.dialect.sequence.PostgreSQLLegacySequenceSupport;
import org.hibernate.community.dialect.sequence.TimesTenSequenceSupport;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.sequence.ANSISequenceSupport;
import org.hibernate.dialect.sequence.DB2SequenceSupport;
import org.hibernate.dialect.sequence.DB2iSequenceSupport;
import org.hibernate.dialect.sequence.DB2zSequenceSupport;
import org.hibernate.dialect.sequence.DerbySequenceSupport;
import org.hibernate.dialect.sequence.H2V1SequenceSupport;
import org.hibernate.dialect.sequence.H2V2SequenceSupport;
import org.hibernate.dialect.sequence.HANASequenceSupport;
import org.hibernate.dialect.sequence.HSQLSequenceSupport;
import org.hibernate.dialect.sequence.LegacyDB2SequenceSupport;
import org.hibernate.dialect.sequence.MariaDBSequenceSupport;
import org.hibernate.dialect.sequence.OracleSequenceSupport;
import org.hibernate.dialect.sequence.PostgreSQLSequenceSupport;
import org.hibernate.dialect.sequence.SQLServer16SequenceSupport;
import org.hibernate.dialect.sequence.SQLServerSequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.dialect.sequence.TiDBSequenceSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementTest {

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("hibernateSequenceStatements")
    void whenHibernateFetchesSequenceValue_shouldIgnoreBothKinds(String dialect, String sql) {
        var detector = new NPlusOneDetector(2, List.of());
        var session = UUID.randomUUID();
        detector.inScope("sequence fetch", () -> {
            for (int i = 0; i < 3; i++) {
                assertThat(detector.inspect(sql)).isSameAs(sql);
                detector.inspect("/* identifier generation */ " + sql);
                detector.beginImplicitLoad(session, "sequence fetch");
                detector.inspect(sql);
                detector.endImplicitLoad(session, "sequence fetch");
            }
        });

        assertThat(detector.drainViolations()).as("%s: %s", dialect, sql).isEmpty();
    }

    static Stream<Arguments> hibernateSequenceStatements() {
        var standard = Stream.of(
                ANSISequenceSupport.INSTANCE, DB2SequenceSupport.INSTANCE, DB2iSequenceSupport.INSTANCE,
                DB2zSequenceSupport.INSTANCE, DerbySequenceSupport.INSTANCE, H2V1SequenceSupport.INSTANCE,
                H2V2SequenceSupport.INSTANCE, HANASequenceSupport.INSTANCE, HSQLSequenceSupport.INSTANCE,
                LegacyDB2SequenceSupport.INSTANCE, new OracleSequenceSupport(DatabaseVersion.make(19)),
                new OracleSequenceSupport(DatabaseVersion.make(23)), PostgreSQLSequenceSupport.INSTANCE,
                // hibernate-community-dialects
                AltibaseSequenceSupport.INSTANCE, FirebirdSequenceSupport.INSTANCE, InformixSequenceSupport.INSTANCE,
                IngresLegacySequenceSupport.INSTANCE, MaxDBSequenceSupport.INSTANCE, MimerSequenceSupport.INSTANCE,
                PostgreSQLLegacySequenceSupport.INSTANCE, TimesTenSequenceSupport.INSTANCE)
                .flatMap(support -> statements(support, "note_seq", "library.note_seq", "\"library\".\"note seq\"",
                        "\"library\".\"note\"\"seq\""));
        var backticks = Stream.of(MariaDBSequenceSupport.INSTANCE, TiDBSequenceSupport.INSTANCE)
                .flatMap(support -> statements(support, "note_seq", "library.note_seq", "`library`.`note seq`",
                        "`library`.`note``seq`"));
        var brackets = Stream.of(SQLServerSequenceSupport.INSTANCE, SQLServer16SequenceSupport.INSTANCE)
                .flatMap(support -> statements(support, "note_seq", "library.note_seq", "[library].[note seq]",
                        "[library].[note]]seq]"));
        return Stream.of(standard, backticks, brackets).flatMap(stream -> stream);
    }

    private static Stream<Arguments> statements(SequenceSupport support, String... names) {
        return Stream.of(names).map(name -> Arguments.of(
                support.getClass().getSimpleName(), support.getSequenceNextValString(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select a.nextval from author a",
            "select \"nextval\" from author",
            "select `nextval` from author",
            "select [nextval] from author",
            "select 'note_seq.nextval from sys.dummy'",
            "select 'nextval for note_seq from sysibm.sysdummy1'",
            "select a.nextval from sys.dummy where a.id=?",
            "select a.nextval from sys.dual where a.id=?",
            "select a.nextval from informix.systables where tabid=2",
            "select next value for note_seq from rdb$database where 1=0",
            "select nextval(a.sequence_name) from author a",
            "select nextval('note_seq'), a.id from author a"
    })
    void whenSequenceWordsArePartOfAnOrdinarySelect_shouldCountIt(String sql) {
        assertThat(SqlStatement.isCountedSelect(sql)).isTrue();
    }
}
