package it.wallettracker.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Una categoria dell'elenco (tabella {@code category}, V12). */
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private CategoryKind kind;

    @Column(name = "position", nullable = false)
    private int position = 1000;

    @Column(name = "builtin", nullable = false)
    private boolean builtin;

    /** Il ruolo (V37): null per quasi tutte. Non cambia con il nome, e la dashboard non lo può modificare. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private CategoryRole role;

    protected Category() {
    }

    public Category(String name, CategoryKind kind) {
        this.name = name;
        this.kind = kind;
    }

    /**
     * Cambia nome e gruppo. Movimenti, regole e budget tengono l'id della categoria (V13), e il ruolo non cambia: il
     * nuovo nome compare ovunque da solo.
     */
    public void update(String name, CategoryKind kind) {
        this.name = name;
        this.kind = kind;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public CategoryKind getKind() {
        return kind;
    }

    public int getPosition() {
        return position;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public CategoryRole getRole() {
        return role;
    }
}
