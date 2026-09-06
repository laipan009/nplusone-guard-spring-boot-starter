package io.github.laipan009.nplusone.sample;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.laipan009.nplusone.core.NPlusOneDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consumer-style test that knows nothing about the guard. Not named *Test or *IT on purpose: only
 * {@link ListenerFailsTestIT} runs it, through the JUnit engine.
 */
@SpringBootTest
@SuppressWarnings("java:S3577") // any name Sonar accepts would also make surefire or failsafe run this class directly
class UnawareSample {

    @Autowired
    private Library library;

    @Autowired
    private BookService bookService;

    @Autowired
    private NPlusOneDetector detector;

    @BeforeEach
    void seed() {
        library.reset();
    }

    @Test
    void lazyAuthors() {
        assertThat(bookService.titlesWithAuthorsLazily()).hasSize(Library.AUTHORS);
    }

    @Test
    void fetchedAuthors() {
        assertThat(bookService.titlesWithAuthorsFetched()).hasSize(Library.AUTHORS);
    }

    /** No request, no outer transaction, nothing wraps the loop: each session loads once and nothing is reported. */
    @Test
    void lazyAuthorsEachInOwnTransaction() {
        assertThat(bookService.titlesEachInOwnTransaction(library.bookIds())).hasSize(Library.AUTHORS);
    }

    /** The same loop wrapped by the test into one unit of work: reported against "describe all books". */
    @Test
    void lazyAuthorsEachInOwnTransactionInScope() {
        var titles = detector.inScope("describe all books",
                () -> bookService.titlesEachInOwnTransaction(library.bookIds()));

        assertThat(titles).hasSize(Library.AUTHORS);
    }

    @Test
    void cacheableCountries() {
        assertThat(bookService.countriesPerAuthor()).hasSize(Library.AUTHORS);
    }

    @Test
    void booksOneByOne() {
        assertThat(bookService.titlesOneByOne(library.bookIds())).hasSize(Library.AUTHORS);
    }

    /** The guard evaluates after Spring completes the test-managed transaction. */
    @Test
    @Transactional
    void lazyAuthorsInsideTestTransaction() {
        assertThat(bookService.titlesWithAuthorsLazily()).hasSize(Library.AUTHORS);
    }
}
