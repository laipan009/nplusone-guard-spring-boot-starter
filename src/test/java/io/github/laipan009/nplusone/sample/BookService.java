package io.github.laipan009.nplusone.sample;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

@Service
public class BookService {

    private final BookRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final PublisherRepository publisherRepository;
    private final MagazineRepository magazineRepository;
    private final NoteRepository noteRepository;
    private final BookDetailsService bookDetailsService;

    public BookService(BookRepository bookRepository, AuthorRepository authorRepository,
                       PublisherRepository publisherRepository, MagazineRepository magazineRepository,
                       NoteRepository noteRepository, BookDetailsService bookDetailsService) {
        this.bookRepository = bookRepository;
        this.authorRepository = authorRepository;
        this.publisherRepository = publisherRepository;
        this.magazineRepository = magazineRepository;
        this.noteRepository = noteRepository;
        this.bookDetailsService = bookDetailsService;
    }

    /** Classic N+1: one select for books, then one proxy initialization per author. */
    @Transactional(readOnly = true)
    public List<String> titlesWithAuthorsLazily() {
        return bookRepository.findAll().stream().map(BookService::describe).toList();
    }

    /** Same result, one select. */
    @Transactional(readOnly = true)
    public List<String> titlesWithAuthorsFetched() {
        return bookRepository.findAllWithAuthor().stream().map(BookService::describe).toList();
    }

    /**
     * No transaction here: findAll runs in its own read-only transaction and the authors are loaded lazily
     * afterwards through the open-in-view session.
     */
    public List<String> titlesWithAuthorsOutsideTransaction() {
        return bookRepository.findAll().stream().map(BookService::describe).toList();
    }

    /** Collection N+1: one lazy collection initialization per author. */
    @Transactional(readOnly = true)
    public List<Integer> bookCountsPerAuthor() {
        return authorRepository.findAll().stream().map(author -> author.getBooks().size()).toList();
    }

    /** Same shape, but the collection is batch fetched: one statement for all publishers. */
    @Transactional(readOnly = true)
    public List<Integer> magazineCountsPerPublisher() {
        return publisherRepository.findAll().stream().map(publisher -> publisher.getMagazines().size()).toList();
    }

    /** EAGER to-one after an HQL query: Hibernate fetches each publisher by a separate select. */
    @Transactional(readOnly = true)
    public List<String> magazinesWithPublishers() {
        return magazineRepository.findAll().stream()
                .map(magazine -> magazine.getTitle() + " by " + magazine.getPublisher().getName()).toList();
    }

    /** Lazy loads of reference data the mapping declares cacheable: reported, not failed. */
    @Transactional(readOnly = true)
    public List<String> countriesPerAuthor() {
        return authorRepository.findAll().stream().map(author -> author.getCountry().getName()).toList();
    }

    /** Explicit repeat: the application itself asks for every book one by one. */
    @Transactional(readOnly = true)
    public List<String> titlesOneByOne(List<Long> ids) {
        return ids.stream().map(id -> bookRepository.findById(id).orElseThrow().getTitle()).toList();
    }

    /**
     * N transactions instead of N+1: no transaction around the loop, each iteration opens its own session and
     * lazily loads one author. No session exceeds the threshold; only the scope around the loop can see it.
     */
    public List<String> titlesEachInOwnTransaction(List<Long> ids) {
        return ids.stream().map(bookDetailsService::describe).toList();
    }

    /** Inserts with a sequence of allocation size 1: one sequence select per row, not a repeated query. */
    @Transactional
    public void writeNotes(int count) {
        IntStream.rangeClosed(1, count).forEach(i -> noteRepository.save(new Note("note " + i)));
    }

    private static String describe(Book book) {
        return book.getTitle() + " by " + book.getAuthor().getName();
    }
}
