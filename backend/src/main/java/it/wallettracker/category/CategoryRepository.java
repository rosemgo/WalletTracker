package it.wallettracker.category;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code category}. */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** La categoria con questo nome, senza badare a maiuscole e minuscole ("spesa" trova "Spesa"). */
    Optional<Category> findByNameIgnoreCase(String name);

    /** La categoria con questo ruolo (V37), se c'è: al massimo una, lo garantisce il database. */
    Optional<Category> findByRole(CategoryRole role);
}
