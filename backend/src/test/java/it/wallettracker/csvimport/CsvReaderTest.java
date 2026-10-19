package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Test "puri" del lettore CSV usato dall'importazione (Apache Commons CSV): gli stessi casi di CsvParserTest,
 * così si vede che libreria e versione didattica danno lo stesso risultato.
 */
class CsvReaderTest {

    @Test
    void readsRowsAndColumnsWithTheItalianSemicolon() {
        assertThat(CsvReader.read("Data;Importo\n03/10/2026;-12,99\n", ';')).containsExactly(
                List.of("Data", "Importo"),
                List.of("03/10/2026", "-12,99"));
    }

    @Test
    void aQuotedValueCanContainTheSeparatorQuotesAndNewLines() {
        String csv = "Descrizione;Importo\n\"Rossi; Mario\";1\n\"Il \"\"Bar\"\"\";2\n\"Prima riga\nseconda riga\";3\n";

        assertThat(CsvReader.read(csv, ';')).containsExactly(
                List.of("Descrizione", "Importo"),
                List.of("Rossi; Mario", "1"),
                List.of("Il \"Bar\"", "2"),
                List.of("Prima riga\nseconda riga", "3"));
    }

    @Test
    void windowsLineEndingsTheExcelBomAndEmptyLinesAreHandled() {
        String csv = "﻿Data,Importo\r\n\r\n03/10/2026,5\r\n;\r\n";

        assertThat(CsvReader.read(csv, ',')).containsExactly(
                List.of("Data", "Importo"),
                List.of("03/10/2026", "5"),
                List.of(";")); // ";" non è il separatore qui: è un valore
    }

    @Test
    void anUnclosedQuoteIsAnError() {
        assertThatThrownBy(() -> CsvReader.read("a;b\n\"aperta;1\n", ';'))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non è un CSV valido");
    }

    @Test
    void theSeparatorIsRecognisedFromTheHeader() {
        assertThat(CsvReader.detectDelimiter("Data;Descrizione;Importo\n1;2;3")).isEqualTo(';');
        assertThat(CsvReader.detectDelimiter("Date,Description,Amount")).isEqualTo(',');
        assertThat(CsvReader.detectDelimiter("Date\tAmount")).isEqualTo('\t');
        // Le virgole dentro le virgolette non contano.
        assertThat(CsvReader.detectDelimiter("\"Data, ora\";\"Importo, EUR\";Note")).isEqualTo(';');
    }
}
