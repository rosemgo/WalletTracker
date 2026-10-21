package it.wallettracker.ai;

/**
 * Chi suggerisce le categorie: nessuno (spento), un modello sul proprio computer con Ollama (passo 7b), oppure
 * un servizio cloud, Mistral AI o Google Gemini (passo 7c).
 */
public enum AiProvider {
    NONE("spento"),
    OLLAMA("Ollama"),
    MISTRAL("Mistral AI"),
    GEMINI("Google Gemini");

    /** Il nome da mostrare nei messaggi. */
    public final String label;

    AiProvider(String label) {
        this.label = label;
    }

    /** È un servizio cloud? Allora servono la chiave e il consenso, e i dati escono dal computer. */
    public boolean cloud() {
        return this == MISTRAL || this == GEMINI;
    }
}
