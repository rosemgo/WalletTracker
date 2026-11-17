package it.wallettracker.category;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import it.wallettracker.language.Language;

/**
 * Le categorie di partenza (V12) e il loro nome in ogni lingua.
 *
 * <p>Nel database una categoria di partenza si riconosce dalla chiave ({@code category.builtin_key}, V43), che è il
 * nome di una di queste voci. Il nome è un dato dell'utente e lo può cambiare: finché è ancora uno di quelli scritti
 * qui, la categoria è "com'era" e segue la lingua dell'app; se l'utente l'ha rinominata, resta come l'ha scritta lui.
 *
 * <p>I nomi italiani sono quelli inseriti dalla V12: lo controlla BuiltinCategoryTest sul database vero.
 */
public enum BuiltinCategory {

    GROCERIES("Spesa alimentare", "Groceries"),
    EATING_OUT("Bar e ristoranti", "Bars and restaurants"),
    BILLS("Spese fisse e utenze", "Bills and utilities"),
    TRANSPORT("Trasporti e auto", "Transport and car"),
    TRAVEL("Viaggi e vacanze", "Travel and holidays"),
    SHOPPING("Shopping", "Shopping"),
    ELECTRONICS("Multimedia ed elettronica", "Media and electronics"),
    LEISURE("Intrattenimento e tempo libero", "Entertainment and leisure"),
    WELLBEING("Salute e benessere", "Health and wellbeing"),
    HEALTHCARE("Cure sanitarie e farmacia", "Healthcare and pharmacy"),
    FAMILY("Famiglia e amici", "Family and friends"),
    EDUCATION("Istruzione e formazione", "Education and training"),
    INSURANCE("Assicurazioni e finanza", "Insurance and finance"),
    TAXES("Tasse e multe", "Taxes and fines"),
    WORK("Spese di lavoro", "Work expenses"),
    CASH("Contanti e prelievi", "Cash and withdrawals"),
    BANK_FEES("Costi bancari", "Bank charges"),
    OTHER_EXPENSES("Altre spese", "Other expenses"),
    SALARY("Stipendio e pensione", "Salary and pension"),
    GIFTS("Regali e altre persone", "Gifts and other people"),
    TAX_REFUNDS("Rimborsi fiscali e bonus", "Tax refunds and bonuses"),
    OTHER_INCOME("Altre entrate", "Other income"),
    COUPONS("Cedole", "Coupons"),
    DIVIDENDS("Dividendi", "Dividends"),
    INTEREST("Interessi", "Interest"),
    CAPITAL_GAIN("Capital gain", "Capital gains tax"),
    TOBIN_TAX("Tobin tax", "Tobin tax"),
    STAMP_DUTY("Imposta di bollo", "Stamp duty"),
    CARD_SETTLEMENT("Saldo carta", "Card settlement");

    private final Map<Language, String> names;

    BuiltinCategory(String italian, String english) {
        this.names = Map.of(Language.IT, italian, Language.EN, english);
    }

    /** Il nome di partenza in una lingua. */
    public String name(Language language) {
        return names.get(language);
    }

    /** Questo nome è ancora uno di quelli di partenza, in una lingua qualsiasi? Maiuscole e minuscole non contano. */
    public boolean isDefaultName(String name) {
        return names.values().stream().anyMatch(name::equalsIgnoreCase);
    }

    public static Optional<BuiltinCategory> of(String key) {
        return Arrays.stream(values()).filter(category -> category.name().equals(key)).findFirst();
    }
}
