package it.wallettracker.message;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageConverterTest {

    private final MessageConverter converter = new MessageConverter();

    @Test
    void aMessageIsSavedAsCodeAndParametersNotAsASentence() {
        String column = converter.convertToDatabaseColumn(Message.of("sync.bank-error", 404));

        assertThat(column).isEqualTo("{\"code\":\"sync.bank-error\",\"args\":[404]}");
        assertThat(TestMessages.text(converter.convertToEntityAttribute(column)))
                .isEqualTo("La banca ha risposto con un errore (codice 404): riproveremo al prossimo aggiornamento.");
    }

    @Test
    void aMessageInsideAnotherSurvivesTheRoundTrip() {
        Message saved = Message.of("link.account-error", "Conto corrente", Message.of("sync.access-denied"));

        Message read = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(saved));

        assertThat(read).isEqualTo(saved);
    }

    @Test
    void noMessageIsAnEmptyColumn() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    /** Le righe scritte dalle versioni precedenti contengono già la frase: si mostra quella, così com'è. */
    @Test
    void aSentenceSavedByAnOlderVersionIsReadAsItIs() {
        Message read = converter.convertToEntityAttribute("consenso scaduto il 2026-10-01: ricollega ING");

        assertThat(read.isVerbatim()).isTrue();
        assertThat(TestMessages.text(read)).isEqualTo("consenso scaduto il 2026-10-01: ricollega ING");
    }

    @Test
    void aTextThatOnlyLooksLikeJsonIsReadAsItIs() {
        assertThat(TestMessages.text(converter.convertToEntityAttribute("{non è json"))).isEqualTo("{non è json");
        assertThat(TestMessages.text(converter.convertToEntityAttribute("{\"altro\":1}"))).isEqualTo("{\"altro\":1}");
    }
}
