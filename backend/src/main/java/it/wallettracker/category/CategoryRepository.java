package it.wallettracker.category;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code category}. */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** La categoria con questo nome, senza badare a maiuscole e minuscole ("spesa" trova "Spesa"). */
    Optional<Category> findByNameIgnoreCase(String name);
}
