package it.wallettracker.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Test "puri" della lettura del codice dell'operazione dal JSON originale della banca. */
class BankCodeTest {

    @Test
    void readsThePartsEachBankFillsIn() {
        // Fineco riempie code, ING description, Revolut code: la forma vera dei loro movimenti, con dati inventati.
        assertThat(BankCode.of("""
                {"bank_transaction_code": {"code": "I18-Interessi Passivi", "sub_code": null, "description": null}}
                """)).isEqualTo("I18-Interessi Passivi");
        assertThat(BankCode.of("""
                {"bank_transaction_code": {"code": null, "sub_code": null, "description": "Accredito Stipendio/Pensione"}}
                """)).isEqualTo("Accredito Stipendio/Pensione");
        assertThat(BankCode.of("""
                {"entry_reference": "x", "bank_transaction_code": {"code": "CARD_PAYMENT"}}
                """)).isEqualTo("CARD_PAYMENT");
    }

    @Test
    void joinsThePartsInOrder() {
        assertThat(BankCode.of("""
                {"bank_transaction_code": {"description": "Pagamento", "sub_code": "ICDT", "code": "PMNT"}}
                """)).isEqualTo("PMNT · ICDT · Pagamento");
    }

    @Test
    void withoutACodeThereIsNothing() {
        assertThat(BankCode.of(null)).isNull();
        assertThat(BankCode.of("{}")).isNull();
        // Le righe dei file CSV: le loro colonne, nessun codice.
        assertThat(BankCode.of("{\"Data\": \"03/10/2026\", \"Importo\": \"-12,99\"}")).isNull();
        assertThat(BankCode.of("{\"bank_transaction_code\": null}")).isNull();
        assertThat(BankCode.of("{\"bank_transaction_code\": {\"code\": null, \"description\": \"  \"}}")).isNull();
    }

    @Test
    void aBrokenJsonIsNoCodeNotAnError() {
        assertThat(BankCode.of("{\"bank_transaction_code\": {\"code\": ")).isNull();
        assertThat(BankCode.of("\"bank_transaction_code\"")).isNull();
    }
}
