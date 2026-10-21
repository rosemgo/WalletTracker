package it.wallettracker.ai;

/**
 * A chi fare una domanda: il fornitore e il modello. Ad esempio (OLLAMA, "gemma3:4b") o
 * (MISTRAL, "mistral-small-latest"). La chiave dei servizi cloud non c'è: la legge {@link AiChatClients} dal
 * database quando serve, così non gira per il programma più del necessario.
 */
public record AiTarget(AiProvider provider, String model) {

    public static AiTarget ollama(String model) {
        return new AiTarget(AiProvider.OLLAMA, model);
    }

    /** "gemma3:4b" per Ollama, "Mistral AI · mistral-small-latest" per il cloud: per i messaggi e i suggerimenti. */
    public String label() {
        return provider.cloud() ? provider.label + " · " + model : model;
    }
}
