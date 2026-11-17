package it.wallettracker.language;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Le impostazioni dell'installazione (tabella {@code app_settings}, V43): una sola riga. Per ora, la lingua. */
@Entity
@Table(name = "app_settings")
public class AppSettings {

    /** L'unica riga della tabella. */
    static final int ID = 1;

    @Id
    private Integer id;

    /** Il codice della lingua ("it", "en"), o null se non è ancora stata scelta. */
    @Column(name = "language")
    private String language;

    protected AppSettings() {
    }

    String getLanguage() {
        return language;
    }

    void setLanguage(String language) {
        this.language = language;
    }
}
