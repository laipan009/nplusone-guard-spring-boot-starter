package io.github.laipan009.nplusone.sample;

import io.github.laipan009.nplusone.core.NPlusOneDetector;
import io.github.laipan009.nplusone.core.Violation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Runs against the real Hibernate wiring. Each test drains the violations it expects, so the listener that runs
 * afterwards sees nothing and the test passes; the listener itself is proven in {@link ListenerFailsTestIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NPlusOneGuardIT {

    @Autowired
    private Library library;

    @Autowired
    private BookService bookService;

    @Autowired
    private NPlusOneDetector detector;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void seed() {
        library.reset();
        detector.drainViolations();
    }

    private List<Violation> violations() {
        return detector.drainViolations();
    }

    @Test
    void whenAuthorsLoadedLazilyInsideTransaction_shouldReportProxyInitialization() {
        assertThat(bookService.titlesWithAuthorsLazily()).hasSize(Library.AUTHORS);

        assertThat(violations()).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.IMPLICIT_LOAD);
            assertThat(violation.subject()).isEqualTo("lazy load of Author (proxy)");
            assertThat(violation.repeats()).isEqualTo(Library.AUTHORS);
            assertThat(violation.scope()).startsWith("session ");
            assertThat(violation.sql()).startsWith("select").contains("from author");
        });
    }

    @Test
    void whenAuthorsJoinFetched_shouldReportNothing() {
        assertThat(bookService.titlesWithAuthorsFetched()).hasSize(Library.AUTHORS);

        assertThat(violations()).isEmpty();
    }

    @Test
    void whenAuthorsLoadedLazilyByOpenInViewOutsideTransaction_shouldReportProxyInitialization() {
        var response = restTemplate.getForEntity("/books/lazy", String[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(Library.AUTHORS);
        assertThat(violations()).extracting(Violation::kind, Violation::subject, Violation::repeats)
                .containsExactly(tuple(Violation.Kind.IMPLICIT_LOAD, "lazy load of Author (proxy)", Library.AUTHORS));
    }

    @Test
    void whenRequestUsesJoinFetch_shouldReportNothing() {
        var response = restTemplate.getForEntity("/books/fetched", String[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(violations()).isEmpty();
    }

    @Test
    void whenLazyCollectionTouchedPerAuthor_shouldReportCollectionInitialization() {
        assertThat(bookService.bookCountsPerAuthor()).hasSize(Library.AUTHORS);

        assertThat(violations()).extracting(Violation::kind, Violation::subject, Violation::repeats)
                .containsExactly(tuple(Violation.Kind.IMPLICIT_LOAD, "lazy load of collection Author.books",
                        Library.AUTHORS));
    }

    @Test
    void whenLazyCollectionIsBatchFetched_shouldReportNothing() {
        assertThat(bookService.magazineCountsPerPublisher()).hasSize(Library.PUBLISHERS);

        assertThat(violations()).isEmpty();
    }

    @Test
    void whenEagerAssociationFetchedBySeparateSelects_shouldReportEagerSelect() {
        assertThat(bookService.magazinesWithPublishers()).hasSize(Library.PUBLISHERS);

        assertThat(violations()).extracting(Violation::kind, Violation::subject, Violation::repeats)
                .containsExactly(tuple(Violation.Kind.IMPLICIT_LOAD, "eager select of Publisher", Library.PUBLISHERS));
    }

    @Test
    void whenCacheableReferenceDataLoadedLazily_shouldReportCacheableLoadInsteadOfFailing() {
        assertThat(bookService.countriesPerAuthor()).hasSize(Library.AUTHORS);

        assertThat(violations()).extracting(Violation::kind, Violation::subject, Violation::repeats)
                .containsExactly(tuple(Violation.Kind.CACHEABLE_LOAD, "lazy load of Country (proxy)",
                        Library.AUTHORS));
    }

    @Test
    void whenApplicationFindsBooksOneByOne_shouldReportExplicitQueryOnly() {
        var ids = library.bookIds();
        violations();

        assertThat(bookService.titlesOneByOne(ids)).hasSize(Library.AUTHORS);

        assertThat(violations()).extracting(Violation::kind, Violation::scope, Violation::repeats)
                .containsExactly(tuple(Violation.Kind.EXPLICIT_QUERY, "transaction", Library.AUTHORS));
    }

    @Test
    void whenRequestFindsBooksOneByOneInSeparateTransactions_shouldReportExplicitQueryPerRequest() {
        var ids = library.bookIds().stream().map(String::valueOf).toList();
        violations();

        var response = restTemplate.getForEntity("/books/one-by-one?ids=" + String.join(",", ids), String[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(violations()).extracting(Violation::kind, Violation::scope)
                .contains(tuple(Violation.Kind.EXPLICIT_QUERY, "HTTP GET /books/one-by-one"))
                .allMatch(t -> t.toArray()[0] == Violation.Kind.EXPLICIT_QUERY);
    }

    /** With open-in-view every small transaction of the request shares one session, so the session reports it. */
    @Test
    void whenRequestLazilyLoadsOneRowPerTransactionUnderOpenInView_shouldReportTheSharedSession() {
        var ids = library.bookIds().stream().map(String::valueOf).toList();
        violations();

        var response = restTemplate.getForEntity("/books/each-own-transaction?ids=" + String.join(",", ids),
                String[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(violations()).filteredOn(v -> v.kind() == Violation.Kind.IMPLICIT_LOAD)
                .extracting(Violation::scope, Violation::subject, Violation::repeats)
                .singleElement().satisfies(t -> {
                    assertThat((String) t.toArray()[0]).startsWith("session ");
                    assertThat(t.toArray()[1]).isEqualTo("lazy load of Author (proxy)");
                    assertThat(t.toArray()[2]).isEqualTo(Library.AUTHORS);
                });
    }

    @Test
    void whenTestWrapsDirectCallInScope_shouldReportImplicitLoadAcrossSessionsAgainstIt() {
        var ids = library.bookIds();
        violations();

        var titles = detector.inScope("describe all books", () -> bookService.titlesEachInOwnTransaction(ids));

        assertThat(titles).hasSize(Library.AUTHORS);
        assertThat(violations()).filteredOn(v -> v.kind() == Violation.Kind.IMPLICIT_LOAD)
                .extracting(Violation::scope, Violation::subject, Violation::repeats)
                .containsExactly(tuple("describe all books", "lazy load of Author (proxy)", Library.AUTHORS));
    }

    @Test
    void whenSequenceOfAllocationSizeOneIsUsedForInserts_shouldReportNothing() {
        bookService.writeNotes(5);

        assertThat(violations()).isEmpty();
    }
}
