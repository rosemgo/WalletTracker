package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Test della versione didattica del lettore CSV (vedi CsvReaderTest per quella usata dall'importazione). */
class CsvParserTest {

    @Test
    void readsRowsAndColumnsWithTheItalianSemicolon() {
        assertThat(CsvParser.parse("Data;Importo\n03/10/2026;-12,99\n", ';')).containsExactly(
                List.of("Data", "Importo"),
                List.of("03/10/2026", "-12,99"));
    }

    @Test
    void aQuotedValueCanContainTheSeparatorQuotesAndNewLines() {
        String csv = "Descrizione;Importo\n\"Rossi; Mario\";1\n\"Il \"\"Bar\"\"\";2\n\"Prima riga\nseconda riga\";3\n";

        assertThat(CsvParser.parse(csv, ';')).containsExactly(
                List.of("Descrizione", "Importo"),
                List.of("Rossi; Mario", "1"),
                List.of("Il \"Bar\"", "2"),
                List.of("Prima riga\nseconda riga", "3"));
    }

    @Test
    void windowsLineEndingsTheExcelBomAndEmptyLinesAreHandled() {
        String csv = "﻿Data,Importo\r\n\r\n03/10/2026,5\r\n;\r\n";

        assertThat(CsvParser.parse(csv, ',')).containsExactly(
                List.of("Data", "Importo"),
                List.of("03/10/2026", "5"),
                List.of(";")); // ";" non è il separatore qui: è un valore
    }

    @Test
    void anUnclosedQuoteIsAnError() {
        assertThatThrownBy(() -> CsvParser.parse("a;b\n\"aperta;1\n", ';'))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("virgoletta aperta");
    }
}
