package it.wallettracker.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import it.wallettracker.classification.ClassificationRule.AmountSign;
import it.wallettracker.classification.ClassificationRule.MatchMode;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.Test;

/** Test "puri" del confronto tra una regola e il testo di un movimento. */
class ClassificationRuleTest {

    private static final BigDecimal EXPENSE = new BigDecimal("-6.56");

    /** Come lo mandano molte banche per i pagamenti con carta: nome, città e paese in campi a larghezza fissa. */
    private static final String CARD_PAYMENT = "MAR GROUP SPA   FOGLIANISE    ITA";

    @Test
    void theNumberOfSpacesDoesNotMatter() {
        // Nella pagina Movimenti gli spazi in più non si vedono (il browser li riduce a uno): chi scrive
        // la regola copiando quello che vede deve trovare il movimento.
        assertThat(rule(MatchMode.CONTAINS, "MAR GROUP SPA FOGLIANISE").matches(CARD_PAYMENT, EXPENSE)).isTrue();
        assertThat(rule(MatchMode.CONTAINS, "spa foglianise ita").matches(CARD_PAYMENT, EXPENSE)).isTrue();
        assertThat(rule(MatchMode.STARTS_WITH, "MAR  GROUP").matches(CARD_PAYMENT, EXPENSE)).isTrue();
        // Il testo copiato tale e quale, con tutti i suoi spazi, funziona ancora.
        assertThat(rule(MatchMode.CONTAINS, CARD_PAYMENT).matches(CARD_PAYMENT, EXPENSE)).isTrue();
    }

    @Test
    void tabsNewLinesAndNonBreakingSpacesCountAsSpaces() {
        assertThat(rule(MatchMode.CONTAINS, "MAR GROUP SPA FOGLIANISE")
                .matches("MAR GROUP\tSPA\nFOGLIANISE", EXPENSE)).isTrue();
    }

    @Test
    void containsStillMeansTheWordsInThatOrderAndNextToEachOther() {
        // "MAR GROUP FOGLIANISE" non c'è: tra GROUP e FOGLIANISE c'è "SPA".
        assertThat(rule(MatchMode.CONTAINS, "MAR GROUP FOGLIANISE").matches(CARD_PAYMENT, EXPENSE)).isFalse();
        assertThat(rule(MatchMode.CONTAINS, "FOGLIANISE").matches(CARD_PAYMENT, EXPENSE)).isTrue();
    }

    @Test
    void theSignOfTheAmountStillCounts() {
        assertThat(rule(MatchMode.CONTAINS, "FOGLIANISE").matches(CARD_PAYMENT, new BigDecimal("6.56"))).isFalse();
    }

    @Test
    void allWordsFindsTheWordsInAnyOrderWithOtherWordsInBetween() {
        assertThat(rule(MatchMode.ALL_WORDS, "MAR GROUP FOGLIANISE").matches(CARD_PAYMENT, EXPENSE)).isTrue();
        assertThat(rule(MatchMode.ALL_WORDS, "foglianise mar").matches(CARD_PAYMENT, EXPENSE)).isTrue();
        // Una parola che manca basta a non trovarlo.
        assertThat(rule(MatchMode.ALL_WORDS, "MAR GROUP AVELLINO").matches(CARD_PAYMENT, EXPENSE)).isFalse();
    }

    @Test
    void allWordsWantsWholeWords() {
        // "SPA" è una parola del testo; "SPAZIO" no, e "FOGLIAN" è solo un pezzo di "FOGLIANISE".
        assertThat(rule(MatchMode.ALL_WORDS, "SPA").matches(CARD_PAYMENT, EXPENSE)).isTrue();
        assertThat(rule(MatchMode.ALL_WORDS, "SPA").matches("SPAZIO GIOCHI", EXPENSE)).isFalse();
        assertThat(rule(MatchMode.ALL_WORDS, "FOGLIAN").matches(CARD_PAYMENT, EXPENSE)).isFalse();
    }

    @Test
    void allWordsIgnoresPunctuationAndCase() {
        assertThat(rule(MatchMode.ALL_WORDS, "temu paypal").matches("PAYPAL *TEMU 1234", EXPENSE)).isTrue();
        assertThat(rule(MatchMode.ALL_WORDS, "netflix").matches("NETFLIX.COM AMSTERDAM", EXPENSE)).isTrue();
        assertThat(rule(MatchMode.ALL_WORDS, "caffè").matches("Caffè Centrale", EXPENSE)).isTrue();
    }

    @Test
    void allWordsWithoutAnyWordFindsNothing() {
        // "Tutte le parole di un elenco vuoto" sarebbe vero per qualsiasi testo: non deve succedere.
        assertThat(rule(MatchMode.ALL_WORDS, "***").matches(CARD_PAYMENT, EXPENSE)).isFalse();
    }

    private static ClassificationRule rule(MatchMode mode, String pattern) {
        return new ClassificationRule("Prova", 100, mode, pattern, AmountSign.NEGATIVE, TransactionType.EXPENSE, null);
    }
}
