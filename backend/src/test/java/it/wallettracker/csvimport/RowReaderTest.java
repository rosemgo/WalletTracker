package it.wallettracker.csvimport;

import static it.wallettracker.message.TestMessages.says;
import static it.wallettracker.message.TestMessages.saysExactly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import it.wallettracker.csvimport.ImportMapping.AmountMode;
import it.wallettracker.csvimport.ImportMapping.DecimalSeparator;
import it.wallettracker.csvimport.RowReader.ParsedRow;
import it.wallettracker.csvimport.RowReader.RowException;
import org.junit.jupiter.api.Test;

/** Test "puri" della lettura di una riga: importi e date scritti in tutti i modi delle banche. */
class RowReaderTest {

    @Test
    void amountsWrittenTheItalianWay() {
        assertThat(RowReader.parseAmount("-1.234,56", DecimalSeparator.COMMA)).isEqualByComparingTo("-1234.56");
        assertThat(RowReader.parseAmount("12,5", DecimalSeparator.COMMA)).isEqualByComparingTo("12.50");
        assertThat(RowReader.parseAmount(" 12,99 € ", DecimalSeparator.COMMA)).isEqualByComparingTo("12.99");
        assertThat(RowReader.parseAmount("+2.000", DecimalSeparator.COMMA)).isEqualByComparingTo("2000");
        assertThat(RowReader.parseAmount("12,50-", DecimalSeparator.COMMA)).isEqualByComparingTo("-12.50");
    }

    @Test
    void amountsWrittenTheEnglishWay() {
        assertThat(RowReader.parseAmount("1,234.56", DecimalSeparator.DOT)).isEqualByComparingTo("1234.56");
        assertThat(RowReader.parseAmount("(12.50)", DecimalSeparator.DOT)).isEqualByComparingTo("-12.50");
        assertThat(RowReader.parseAmount("EUR -9.99", DecimalSeparator.DOT)).isEqualByComparingTo("-9.99");
    }

    @Test
    void wrongAmountsAreRefusedWithAClearMessage() {
        assertThat(RowReader.parseAmount("  ", DecimalSeparator.COMMA)).isNull();
        assertThatThrownBy(() -> RowReader.parseAmount("dodici", DecimalSeparator.COMMA))
                .isInstanceOf(RowException.class).satisfies(saysExactly("l'importo \"dodici\" non è un numero"));
        // Letto con il separatore sbagliato, "1,2345" non è un importo in euro: meglio un errore che un numero sbagliato.
        assertThatThrownBy(() -> RowReader.parseAmount("1,2345", DecimalSeparator.COMMA))
                .isInstanceOf(RowException.class).satisfies(says("più di due decimali"));
    }

    @Test
    void datesInTheChosenFormatWithOrWithoutTime() {
        assertThat(RowReader.parseDate("03/10/2026", "dd/MM/yyyy")).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(RowReader.parseDate("03.10.2026", "dd.MM.yyyy")).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(RowReader.parseDate("03/10/26", "dd/MM/yy")).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(RowReader.parseDate("2026-10-03 10:12:00", "yyyy-MM-dd")).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(RowReader.parseDate("2026-10-03T10:12:00Z", "yyyy-MM-dd")).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void impossibleOrWronglyFormattedDatesAreErrors() {
        // Il 31 febbraio non esiste: con ResolverStyle.STRICT è un errore, non il 3 marzo.
        assertThatThrownBy(() -> RowReader.parseDate("31/02/2026", "dd/MM/yyyy"))
                .isInstanceOf(RowException.class).satisfies(saysExactly("la data \"31/02/2026\" non è nel formato gg/mm/aaaa"));
        assertThatThrownBy(() -> RowReader.parseDate("2026-10-03", "dd/MM/yyyy")).isInstanceOf(RowException.class);
        assertThatThrownBy(() -> RowReader.parseDate("", "dd/MM/yyyy")).satisfies(saysExactly("manca la data"));
        assertThatThrownBy(() -> RowReader.parseDate("03/10/2026", "yyyyMMdd")).satisfies(says("non previsto"));
    }

    @Test
    void aWholeRowWithOneSignedAmountColumn() {
        ImportMapping mapping = new ImportMapping(";", 0, "dd/MM/yyyy", AmountMode.SINGLE, 3, null, null,
                DecimalSeparator.COMMA, 1, 2, null, null, null, null);

        ParsedRow row = RowReader.read(List.of("03/10/2026", "  Pagamento   carta ", "NETFLIX.COM", "-12,99"),
                mapping, "EUR");

        assertThat(row).isEqualTo(new ParsedRow(LocalDate.of(2026, 10, 3), new BigDecimal("-12.99"),
                "EUR", "Pagamento carta", "NETFLIX.COM", null));
    }

    @Test
    void incomeAndExpenseInTwoColumnsBecomeOneSignedAmount() {
        ImportMapping mapping = new ImportMapping(";", 0, "dd/MM/yyyy", AmountMode.SPLIT, null, 1, 2,
                DecimalSeparator.COMMA, 3, null, null, null, null, null);

        assertThat(RowReader.read(List.of("01/10/2026", "2.100,00", "", "Stipendio"), mapping, "EUR").amount())
                .isEqualByComparingTo("2100.00");
        // Le uscite diventano negative, che nel file abbiano il segno o no.
        assertThat(RowReader.read(List.of("02/10/2026", "", "85,40", "Spesa"), mapping, "EUR").amount())
                .isEqualByComparingTo("-85.40");
        assertThat(RowReader.read(List.of("02/10/2026", "", "-85,40", "Spesa"), mapping, "EUR").amount())
                .isEqualByComparingTo("-85.40");
        assertThatThrownBy(() -> RowReader.read(List.of("02/10/2026", "", "", "?"), mapping, "EUR"))
                .satisfies(saysExactly("mancano sia l'entrata sia l'uscita"));
    }

    @Test
    void amountsWithoutSignTakeTheSignFromTheWordsOfTheIncome() {
        // Come il file di Tinaba (docs/tinaba.md), con dati inventati: data, descrizione, importo sempre positivo.
        ImportMapping mapping = new ImportMapping(",", 0, "dd/MM/yyyy", AmountMode.UNSIGNED, 2, null, null,
                DecimalSeparator.COMMA, 1, null, null, 1, " Ricevuto , ricevuta,, ", null);

        assertThat(mapping.incomeWordList()).containsExactly("ricevuto", "ricevuta");
        assertThat(RowReader.read(List.of("28/10/2026 01:32:31", "Ricevuto da Mario Rossi", "200"), mapping, "EUR")
                .amount()).isEqualByComparingTo("200.00");
        assertThat(RowReader.read(List.of("13/10/2026", "Trasferimento RICEVUTO per campagna cashback", "46,74"),
                mapping, "EUR").amount()).isEqualByComparingTo("46.74");
        assertThat(RowReader.read(List.of("02/10/2026", "Nuovo versamento Robo advisor", "200"), mapping, "EUR")
                .amount()).isEqualByComparingTo("-200.00");
        // Un segno nel file non conta: decide il testo.
        assertThat(RowReader.read(List.of("07/04/2026", "Ricarica effettuata", "-50"), mapping, "EUR").amount())
                .isEqualByComparingTo("-50.00");
        assertThat(RowReader.read(List.of("07/04/2026", "Ricevuto da Mario Rossi", "-50"), mapping, "EUR")
                .amount()).isEqualByComparingTo("50.00");
    }

    @Test
    void aSecondTextColumnIsAddedToTheDescription() {
        // Tinaba (docs/tinaba.md, parte H): "Descrizione" e "Causale/Messaggio" diventano una causale sola.
        ImportMapping mapping = new ImportMapping(",", 0, "dd/MM/yyyy", AmountMode.SINGLE, 3, null, null,
                DecimalSeparator.COMMA, 1, null, null, null, null, 2);

        ParsedRow both = RowReader.read(List.of("28/10/2026", "Ricevuto da Mario Rossi", " Trasferimento a mio conto ",
                "200"), mapping, "EUR");
        assertThat(both.description()).isEqualTo("Ricevuto da Mario Rossi");
        assertThat(both.detail()).isEqualTo("Trasferimento a mio conto");
        assertThat(both.fullDescription()).isEqualTo("Ricevuto da Mario Rossi · Trasferimento a mio conto");

        // Seconda colonna vuota: la causale resta com'è. Causale vuota: resta il solo testo della seconda.
        assertThat(RowReader.read(List.of("02/10/2026", "Nuovo versamento", "", "-200"), mapping, "EUR")
                .fullDescription()).isEqualTo("Nuovo versamento");
        assertThat(RowReader.read(List.of("02/10/2026", "", "Regalo", "50"), mapping, "EUR").fullDescription())
                .isEqualTo("Regalo");
    }

    @Test
    void theCurrencyComesFromItsColumnOrFromTheAccount() {
        ImportMapping mapping = new ImportMapping(",", 0, "yyyy-MM-dd", AmountMode.SINGLE, 1, null, null,
                DecimalSeparator.DOT, null, null, 2, null, null, null);

        assertThat(RowReader.read(List.of("2026-10-03", "-40.00", "usd"), mapping, "EUR").currency()).isEqualTo("USD");
        assertThat(RowReader.read(List.of("2026-10-03", "-40.00", ""), mapping, "EUR").currency()).isEqualTo("EUR");
        assertThatThrownBy(() -> RowReader.read(List.of("2026-10-03", "-40.00", "Euro"), mapping, "EUR"))
                .satisfies(says("non è un codice come EUR"));
    }
}
