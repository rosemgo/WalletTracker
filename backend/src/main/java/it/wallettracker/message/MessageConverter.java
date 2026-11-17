package it.wallettracker.message;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Come un {@link Message} si salva in una colonna di testo: il codice e i parametri in JSON, {@code
 * {"code":"sync.http-error","args":[404]}}. Non la frase: quella si scrive quando qualcuno la legge, nella sua lingua.
 *
 * <p>{@code AttributeConverter} è il modo di JPA per dire come un tipo Java diventa una colonna, e viceversa; il campo
 * dell'entità lo indica con {@code @Convert(converter = MessageConverter.class)}.
 *
 * <p>Le righe salvate prima di questo convertitore contengono già una frase in italiano: si leggono come testo così
 * com'è ({@link Message#verbatim}). Spariscono da sole al primo aggiornamento del conto, che riscrive la colonna.
 */
@Converter
public class MessageConverter implements AttributeConverter<Message, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(Message message) {
        return message == null ? null : write(message).toString();
    }

    @Override
    public Message convertToEntityAttribute(String column) {
        if (column == null) {
            return null;
        }
        if (column.startsWith("{")) {
            try {
                Message message = read(JSON.readTree(column));
                if (message != null) {
                    return message;
                }
            } catch (JacksonException e) {
                // non è il nostro JSON: è un testo che comincia per caso con una graffa
            }
        }
        return Message.verbatim(column);
    }

    private static ObjectNode write(Message message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("code", message.code());
        ArrayNode args = node.putArray("args");
        for (Object arg : message.args()) {
            switch (arg) {
                case Message inner -> args.add(write(inner));
                case Integer number -> args.add(number);
                case Long number -> args.add(number);
                case Number number -> args.add(number.doubleValue());
                default -> args.add(String.valueOf(arg));
            }
        }
        return node;
    }

    private static Message read(JsonNode node) {
        if (!node.isObject() || !node.path("code").isString() || !node.path("args").isArray()) {
            return null;
        }
        List<Object> args = new ArrayList<>();
        for (JsonNode arg : node.path("args")) {
            if (arg.isObject()) {
                Message inner = read(arg);
                if (inner == null) {
                    return null;
                }
                args.add(inner);
            } else if (arg.isIntegralNumber()) {
                args.add(arg.asLong());
            } else if (arg.isNumber()) {
                args.add(arg.asDouble());
            } else {
                args.add(arg.asString());
            }
        }
        return new Message(node.path("code").asString(), args);
    }
}
