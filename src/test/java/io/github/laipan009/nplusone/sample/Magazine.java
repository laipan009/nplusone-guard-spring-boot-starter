package io.github.laipan009.nplusone.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class Magazine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    /** EAGER to-one: after an HQL query Hibernate fetches each publisher by a separate select. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Publisher publisher;

    protected Magazine() {
    }

    public Magazine(String title, Publisher publisher) {
        this.title = title;
        this.publisher = publisher;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Publisher getPublisher() {
        return publisher;
    }
}
