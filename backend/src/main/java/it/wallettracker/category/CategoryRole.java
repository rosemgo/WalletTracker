package it.wallettracker.category;

/**
 * Il ruolo di una categoria (colonna {@code role}, V37): dice al codice "questa è la categoria di...", qualunque sia il
 * suo nome. Quasi tutte le categorie non ne hanno uno: per il codice sono solo un nome scelto dall'utente.
 *
 * <p>Perché non il nome: il nome è dell'utente, che lo può cambiare (o scrivere in un'altra lingua). Se il codice
 * cercasse "Ritenute", rinominarla in "Ritenute fiscali" farebbe sbagliare i conti senza nessun avviso
 * (docs/investimenti.md, parte T). Il ruolo invece non cambia mai.
 */
public enum CategoryRole {

    /**
     * Le ritenute sulle rendite (la categoria che all'inizio si chiama "Ritenute"): le tasse con questa categoria si
     * tolgono dalle cedole, dai dividendi e dagli interessi, e danno il netto (Investments.withholding).
     */
    WITHHOLDING
}
