package io.github.laipan009.nplusone.sample;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookDetailsService {

    private final BookRepository bookRepository;

    public BookDetailsService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    /** One small transaction: one explicit find, one lazy load. Harmless alone, an N+1 when called in a loop. */
    @Transactional(readOnly = true)
    public String describe(Long id) {
        var book = bookRepository.findById(id).orElseThrow();
        return book.getTitle() + " by " + book.getAuthor().getName();
    }
}
