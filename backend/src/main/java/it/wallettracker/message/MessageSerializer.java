package it.wallettracker.message;

import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Come un {@link Message} diventa JSON nelle risposte: una stringa, la frase nella lingua della richiesta.
 *
 * <p>Un DTO dichiara il campo come {@code Message error} invece di {@code String error}; la dashboard riceve
 * comunque {@code "error": "La banca non ha accettato l'accesso..."}. Il codice viaggia dentro il backend, la frase
 * nasce all'uscita.
 *
 * <p>{@code @JacksonComponent} registra questo serializzatore nel JsonMapper di Spring (quello delle risposte REST).
 */
@JacksonComponent
public class MessageSerializer extends ValueSerializer<Message> {

    private final Messages messages;

    public MessageSerializer(Messages messages) {
        this.messages = messages;
    }

    @Override
    public void serialize(Message value, JsonGenerator generator, SerializationContext context) {
        generator.writeString(messages.text(value));
    }
}
