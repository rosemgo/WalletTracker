package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import it.wallettracker.csvimport.ImportMapping.AmountMode;
import it.wallettracker.csvimport.ImportMapping.DecimalSeparator;
import org.junit.jupiter.api.Test;

/** Test "puri" della proposta di mappatura, con intestazioni come quelle delle banche. */
class ColumnGuesserTest {

    @Test
    void anItalianExportWithOneAmountColumn() {
        List<String> headers = List.of("Data operazione", "Data valuta", "Descrizione", "Importo");
        List<List<String>> samples = List.of(List.of("03/10/2026", "04/10/2026", "NETFLIX", "-12,99"));

        ImportMapping mapping = ColumnGuesser.guess(";", headers, samples);

        assertThat(mapping.dateColumn()).isZero();          // "Data operazione", non "Data valuta"
        assertThat(mapping.dateFormat()).isEqualTo("dd/MM/yyyy");
        assertThat(mapping.amountMode()).isEqualTo(AmountMode.SINGLE);
        assertThat(mapping.amountColumn()).isEqualTo(3);
        assertThat(mapping.decimalSeparator()).isEqualTo(DecimalSeparator.COMMA);
        assertThat(mapping.descriptionColumn()).isEqualTo(2);
        assertThat(mapping.currencyColumn()).isNull();      // "Data valuta" non è la valuta!
    }

    @Test
    void theTinabaFileIsRecognizedAndItsAmountsAreReadWithoutSign() {
        // Le colonne del file di Tinaba, con valori inventati (docs/tinaba.md).
        List<String> headers = List.of("Data", "Categoria", "Controparte", "Descrizione", "Causale/Messaggio",
                "CRO/NOP", "Importo", "Community");
        List<List<String>> samples = List.of(
                List.of("28/10/2026 01:32:31", "Bonifici", "Mario Rossi", "Ricevuto da Mario Rossi", "", "ABC", "200", "no"),
                List.of("13/10/2026 15:52:28", "PAYMENT_TRANSFER", "Tinaba Cashback", "Trasferimento ricevuto",
                        "", "", "46,74", "no"));

        ImportMapping mapping = ColumnGuesser.guess(",", headers, samples);

        assertThat(mapping.amountMode()).isEqualTo(AmountMode.UNSIGNED);
        assertThat(mapping.amountColumn()).isEqualTo(6);
        assertThat(mapping.signColumn()).isEqualTo(3); // la descrizione
        assertThat(mapping.detailColumn()).isEqualTo(4); // "Causale/Messaggio", aggiunta alla causale
        assertThat(mapping.incomeWordList()).containsExactly("ricevuto", "ricevuta");
        assertThat(mapping.dateFormat()).isEqualTo("dd/MM/yyyy");
        assertThat(mapping.decimalSeparator()).isEqualTo(DecimalSeparator.COMMA);
        assertThat(mapping.descriptionColumn()).isEqualTo(3);
        assertThat(mapping.counterpartyColumn()).isEqualTo(2);
        // Un file con le stesse parole ma colonne diverse non è di Tinaba: resta la proposta normale.
        assertThat(ColumnGuesser.guess(",", List.of("Data", "Descrizione", "Importo"), List.of()).amountMode())
                .isEqualTo(AmountMode.SINGLE);
    }

    @Test
    void anExportWithIncomeAndExpenseColumns() {
        List<String> headers = List.of("Data", "Entrate", "Uscite", "Descrizione");
        List<List<String>> samples = List.of(List.of("01/10/2026", "2.100,00", "", "Stipendio"));

        ImportMapping mapping = ColumnGuesser.guess(";", headers, samples);

        assertThat(mapping.amountMode()).isEqualTo(AmountMode.SPLIT);
        assertThat(mapping.incomeColumn()).isEqualTo(1);
        assertThat(mapping.expenseColumn()).isEqualTo(2);
        assertThat(mapping.amountColumn()).isNull();
        assertThat(mapping.decimalSeparator()).isEqualTo(DecimalSeparator.COMMA);
    }

    @Test
    void anEnglishExportWithIsoDatesPayeeAndCurrency() {
        List<String> headers = List.of("Date", "Payee", "Amount", "Currency");
        List<List<String>> samples = List.of(List.of("2026-10-03", "Spotify AB", "-10.99", "EUR"));

        ImportMapping mapping = ColumnGuesser.guess(",", headers, samples);

        assertThat(mapping.dateFormat()).isEqualTo("yyyy-MM-dd");
        assertThat(mapping.decimalSeparator()).isEqualTo(DecimalSeparator.DOT);
        assertThat(mapping.counterpartyColumn()).isEqualTo(1);
        assertThat(mapping.currencyColumn()).isEqualTo(3);
    }

    @Test
    void unknownHeadersLeaveTheChoiceToTheUser() {
        ImportMapping mapping = ColumnGuesser.guess(";", List.of("A", "B"), List.of(List.of("x", "y")));

        assertThat(mapping.dateColumn()).isNull();
        assertThat(mapping.amountColumn()).isNull();
    }
}
