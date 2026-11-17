package it.wallettracker.message;

import java.util.Arrays;
import java.util.List;

/**
 * Un testo per chi usa l'app, <b>prima</b> di essere scritto in una lingua: il codice della frase e i suoi parametri.
 *
 * <p>Il backend non scrive frasi: dice <i>quale</i> frase serve ({@code "category.duplicate"}) e con quali valori
 * ({@code "Viaggi"}). La frase vera sta nel catalogo {@code messages.properties} e nasce solo quando la risposta
 * esce verso la dashboard ({@link Messages}, {@link MessageSerializer}): così la lingua è quella di chi legge in
 * quel momento, non quella di quando il messaggio è stato creato. Conta soprattutto per i messaggi che si salvano
 * (l'errore dell'ultimo aggiornamento di un conto, scritto di notte dal timer e letto il giorno dopo).
 *
 * <p>Accanto a ogni codice, nel codice Java, un commento riporta la frase italiana: chi legge sa che cosa vede
 * l'utente senza aprire il catalogo.
 *
 * @param code il codice della frase nel catalogo, oppure {@link #VERBATIM}
 * @param args i parametri, nell'ordine dei segnaposto {@code {0}}, {@code {1}}...: testi, numeri o altri messaggi
 *             (un messaggio dentro un altro: "Riga 3: manca la data.")
 */
public record Message(String code, List<Object> args) {

    /** Il codice dei testi che non sono nostri e non si traducono: la risposta di una banca, di un servizio. */
    public static final String VERBATIM = "verbatim";

    public Message {
        args = List.copyOf(args);
    }

    /**
     * Un messaggio del catalogo. I parametri che non sono testi, numeri o messaggi (una data, un mese, un enum)
     * diventano il loro testo: {@code LocalDate} → "2026-11-14".
     */
    public static Message of(String code, Object... args) {
        return new Message(code, Arrays.stream(args).map(Message::plain).toList());
    }

    /** Un testo così com'è, che non passa dal catalogo: quello che ha risposto un servizio esterno. */
    public static Message verbatim(String text) {
        return new Message(VERBATIM, List.of(String.valueOf(text)));
    }

    public boolean isVerbatim() {
        return VERBATIM.equals(code);
    }

    private static Object plain(Object arg) {
        return arg instanceof Message || arg instanceof Number || arg instanceof String ? arg : String.valueOf(arg);
    }

    /** Per i log: il codice e i parametri ({@code sync.consent-expired[2026-11-14, ING]}), o il testo così com'è. */
    @Override
    public String toString() {
        return isVerbatim() ? String.valueOf(args.getFirst()) : code + args;
    }
}
