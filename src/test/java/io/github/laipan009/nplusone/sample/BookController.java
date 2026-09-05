package io.github.laipan009.nplusone.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping("/books/lazy")
    public List<String> lazy() {
        return bookService.titlesWithAuthorsOutsideTransaction();
    }

    @GetMapping("/books/fetched")
    public List<String> fetched() {
        return bookService.titlesWithAuthorsFetched();
    }

    @GetMapping("/books/each-own-transaction")
    public List<String> eachInOwnTransaction(@RequestParam List<Long> ids) {
        return bookService.titlesEachInOwnTransaction(ids);
    }

    @GetMapping("/books/one-by-one")
    public List<String> oneByOne(@RequestParam List<Long> ids) {
        return bookService.titlesOneByOne(ids);
    }
}
