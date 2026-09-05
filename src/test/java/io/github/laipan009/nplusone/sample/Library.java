package io.github.laipan009.nplusone.sample;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Seeds enough rows that loading them one by one crosses the default threshold.
 */
@Component
public class Library {

    public static final int AUTHORS = 5;
    public static final int PUBLISHERS = 5;

    private final AuthorRepository authorRepository;
    private final BookRepository bookRepository;
    private final PublisherRepository publisherRepository;
    private final MagazineRepository magazineRepository;
    private final NoteRepository noteRepository;
    private final CountryRepository countryRepository;

    public Library(AuthorRepository authorRepository, BookRepository bookRepository,
                   PublisherRepository publisherRepository, MagazineRepository magazineRepository,
                   NoteRepository noteRepository, CountryRepository countryRepository) {
        this.authorRepository = authorRepository;
        this.bookRepository = bookRepository;
        this.publisherRepository = publisherRepository;
        this.magazineRepository = magazineRepository;
        this.noteRepository = noteRepository;
        this.countryRepository = countryRepository;
    }

    /**
     * Bulk deletes on purpose: {@code deleteAll()} loads every row first, and the EAGER publisher of each magazine
     * would then be fetched by a separate select. The guard reported exactly that when this fixture used it.
     *
     * <p>Runs in its own transaction so that a {@code @Transactional} test starts with an empty session: rows
     * seeded inside the test's own session sit in the first-level cache and are never lazily loaded, which is the
     * usual reason transactional tests hide N+1.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset() {
        noteRepository.deleteAllInBatch();
        magazineRepository.deleteAllInBatch();
        publisherRepository.deleteAllInBatch();
        bookRepository.deleteAllInBatch();
        authorRepository.deleteAllInBatch();
        countryRepository.deleteAllInBatch();
        IntStream.rangeClosed(1, AUTHORS).forEach(i -> {
            var country = countryRepository.save(new Country("Country " + i));
            var author = authorRepository.save(new Author("Author " + i, country));
            bookRepository.save(new Book("Book " + i, author));
        });
        IntStream.rangeClosed(1, PUBLISHERS).forEach(i -> {
            var publisher = publisherRepository.save(new Publisher("Publisher " + i));
            magazineRepository.save(new Magazine("Magazine " + i, publisher));
        });
    }

    public List<Long> bookIds() {
        return bookRepository.findAll().stream().map(Book::getId).toList();
    }
}
