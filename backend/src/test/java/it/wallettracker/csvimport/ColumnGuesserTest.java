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
