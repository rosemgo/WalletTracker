package it.wallettracker.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Test "puri" della chiave dell'esercente. */
class MerchantKeyTest {

    private static final List<Set<String>> NO_OWNERS = List.of();

    /** Il titolare del conto (inventato), come lo dà ClassificationService.ownerNames. */
    private static final List<Set<String>> OWNER = List.of(Set.of("MARIO", "ROSSI"));

    @Test
    void datesTimesAndNumbersDisappearSoTheSameMerchantHasTheSameKey() {
        String october = MerchantKey.of(null, "Pagamento carta del 03/10/2026 alle 10:12 presso NETFLIX.COM 4521",
                NO_OWNERS);
        String november = MerchantKey.of(null, "Pagamento carta del 03/11/2026 alle 09:58 presso Netflix.com 4521",
                NO_OWNERS);

        assertThat(october).isEqualTo("PAGAMENTO CARTA DEL ALLE PRESSO NETFLIX COM");
        assertThat(november).isEqualTo(october);
    }

    @Test
    void theCounterpartyIsPreferredToTheDescription() {
        assertThat(MerchantKey.of("Spotify AB", "Pagamento 123", NO_OWNERS)).isEqualTo("SPOTIFY AB");
        assertThat(MerchantKey.of("  ", "Palestra Fit", NO_OWNERS)).isEqualTo("PALESTRA FIT");
    }

    @Test
    void accentedLettersAreKept() {
        assertThat(MerchantKey.of("Caffè Città", null, NO_OWNERS)).isEqualTo("CAFFÈ CITTÀ");
    }

    @Test
    void withoutUsefulTextThereIsNoKey() {
        assertThat(MerchantKey.of(null, null, NO_OWNERS)).isNull();
        assertThat(MerchantKey.of(null, "12/10/2026 4521", NO_OWNERS)).isNull();
    }

    @Test
    void longTextsAreCut() {
        assertThat(MerchantKey.of("A".repeat(100), null, NO_OWNERS)).hasSize(MerchantKey.MAX_LENGTH);
    }

    @Test
    void theProposedNameHasNoDatesOrNumbers() {
        assertThat(MerchantKey.displayName("Pagamento carta del 14/10/2026 alle 10:10 presso NETFLIX.COM 4521"))
                .isEqualTo("Pagamento carta del alle presso NETFLIX.COM");
        assertThat(MerchantKey.displayName("Spotify AB")).isEqualTo("Spotify AB");
        assertThat(MerchantKey.displayName("12345")).isEqualTo("12345"); // se resta vuoto, meglio il testo intero
    }

    @Test
    void orderCodesWithDigitsDisappearWholeNotJustTheirDigits() {
        // Testi veri di Amazon: il codice d'ordine cambia a ogni movimento. Togliendo solo le cifre ne
        // resterebbero le lettere ("BT YM", "BT BJ CA"), diverse ogni volta.
        assertThat(MerchantKey.of(null, "AMAZON* BT9YM4924      LUXEMBOURG     LUX", NO_OWNERS))
                .isEqualTo("AMAZON LUXEMBOURG LUX");
        assertThat(MerchantKey.of(null, "AMAZON* BT2BJ4CA4      LUXEMBOURG     LUX", NO_OWNERS))
                .isEqualTo("AMAZON LUXEMBOURG LUX");
        assertThat(MerchantKey.of("Amzn Mktp It*935vf3qd5", "Amzn Mktp It", NO_OWNERS)).isEqualTo("AMZN MKTP IT");
        assertThat(MerchantKey.of("Amazon.it", "amazon.it", NO_OWNERS)).isEqualTo("AMAZON IT");
    }

    @Test
    void whenTheCounterpartyIsTheAccountOwnerTheMerchantIsInTheDescription() {
        // Alcune banche mettono come controparte il titolare e il negozio nella causale. Con la controparte,
        // tutti questi movimenti, di negozi diversi, avrebbero la stessa chiave: il nome del titolare.
        assertThat(MerchantKey.of("MARIO ROSSI", "amazon.it", OWNER)).isEqualTo("AMAZON IT");
        assertThat(MerchantKey.of("Rossi Mario", "ESSELUNGA MILANO", OWNER)).isEqualTo("ESSELUNGA MILANO");
        assertThat(MerchantKey.merchantText("MARIO ROSSI", "amazon.it", OWNER)).isEqualTo("amazon.it");
        // Un'altra persona resta la controparte, e "a favore di Mario Rossi" non è il nome esatto.
        assertThat(MerchantKey.of("LUCA BIANCHI", "Bonifico", OWNER)).isEqualTo("LUCA BIANCHI");
        assertThat(MerchantKey.of("Bonifico a favore di Mario Rossi", null, OWNER))
                .isEqualTo("BONIFICO A FAVORE DI MARIO ROSSI");
        // Solo il nome del titolare, senza causale: nessun esercente.
        assertThat(MerchantKey.of("MARIO ROSSI", null, OWNER)).isNull();
    }

    /** Due bonifici in arrivo dalla stessa banca (inventati): la causale è uguale fino al nome di chi paga. */
    private static final String REFUND = "Bonifico N. 00000000000000000000000001IT BIC Ordinante ALFAITMMXXX "
            + "Codifica Ordinante IT00X0000000000000000000001 Anagrafica Ordinante ACME SRL Note: Rimborso";
    private static final String SALARY = "Bonifico N. 00000000000000000000000002IT BIC Ordinante ALFAITMMXXX "
            + "Codifica Ordinante IT00X0000000000000000000002 Anagrafica Ordinante BETA SPA Note: Stipendio ottobre";

    @Test
    void theNameAfterALabelInTheDescriptionIsTheMerchant() {
        // Prima la chiave era l'inizio della causale, tagliato a 60 caratteri: uguale per tutti e due.
        assertThat(MerchantKey.of(null, REFUND, OWNER)).isEqualTo("ACME SRL");
        assertThat(MerchantKey.of(null, SALARY, OWNER)).isEqualTo("BETA SPA");
        assertThat(MerchantKey.of("MARIO ROSSI", SALARY, OWNER)).isEqualTo("BETA SPA");
        assertThat(MerchantKey.merchantText(null, REFUND, OWNER)).isEqualTo("ACME SRL");
        // Senza le note il nome arriva fino in fondo; anche il bonifico in uscita.
        assertThat(MerchantKey.of(null, "Bonifico N. 1 Anagrafica Beneficiario Gamma Servizi S.r.l.", OWNER))
                .isEqualTo("GAMMA SERVIZI S R L");
    }

    @Test
    void theCreditorOfADirectDebitIsTheMerchantNotTheDebtor() {
        String debit = "Addebito SDD CORE Scad. 01/10/2026 Imp. 45,00 Creditor id. IT00ZZZ0000000000 "
                + "GAMMA LUCE E GAS S.P.A. Id Mandato 0000000001 Debitore MARIO ROSSI";

        assertThat(MerchantKey.merchantText(null, debit, OWNER)).isEqualTo("GAMMA LUCE E GAS S.P.A.");
        assertThat(MerchantKey.of(null, debit, OWNER)).isEqualTo("GAMMA LUCE E GAS S P A");
    }

    @Test
    void withoutANameAfterTheLabelTheWholeDescriptionStays() {
        assertThat(MerchantKey.nameInDescription("Anagrafica Ordinante Note: niente")).isNull();
        assertThat(MerchantKey.nameInDescription("Anagrafica Ordinante 12345")).isNull();
        assertThat(MerchantKey.nameInDescription("Pagamento POS ESSELUNGA")).isNull();
        assertThat(MerchantKey.of(null, "Anagrafica Ordinante 12345", OWNER)).isEqualTo("ANAGRAFICA ORDINANTE");
    }

    @Test
    void theFirstWordIsTheLooseComparisonForTheMenu() {
        assertThat(MerchantKey.firstWord("AMAZON LUXEMBOURG LUX")).isEqualTo("AMAZON");
        assertThat(MerchantKey.firstWord("AMAZON IT")).isEqualTo("AMAZON");
        assertThat(MerchantKey.firstWord(null)).isNull();
    }
}
