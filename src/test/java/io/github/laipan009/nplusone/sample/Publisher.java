package io.github.laipan009.nplusone.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

@Entity
public class Publisher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Lazy collection with batch fetching: all publishers' magazines load in one statement. */
    @OneToMany(mappedBy = "publisher")
    @BatchSize(size = 20)
    private List<Magazine> magazines = new ArrayList<>();

    protected Publisher() {
    }

    public Publisher(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<Magazine> getMagazines() {
        return magazines;
    }
}
