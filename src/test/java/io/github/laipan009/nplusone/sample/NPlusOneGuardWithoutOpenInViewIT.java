package io.github.laipan009.nplusone.sample;

import io.github.laipan009.nplusone.core.NPlusOneDetector;
import io.github.laipan009.nplusone.core.Violation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Without open-in-view each small transaction of a request is its own session: no session exceeds the threshold,
 * and only the scope around the request sees the loop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.open-in-view=false")
class NPlusOneGuardWithoutOpenInViewIT {

    @Autowired
    private Library library;

    @Autowired
    private NPlusOneDetector detector;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void seed() {
        library.reset();
        detector.drainViolations();
    }

    @Test
    void whenRequestLazilyLoadsOneRowPerTransaction_shouldReportTheRequest() {
        var ids = library.bookIds().stream().map(String::valueOf).toList();
        detector.drainViolations();

        var response = restTemplate.getForEntity("/books/each-own-transaction?ids=" + String.join(",", ids),
                String[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detector.drainViolations()).filteredOn(v -> v.kind() == Violation.Kind.IMPLICIT_LOAD)
                .extracting(Violation::scope, Violation::subject, Violation::repeats)
                .containsExactly(tuple("HTTP GET /books/each-own-transaction", "lazy load of Author (proxy)",
                        Library.AUTHORS));
    }
}
