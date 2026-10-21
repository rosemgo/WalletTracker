package it.wallettracker.ai;

/** Come il backend parla con il modello: con Spring AI, o con RestClient scrivendo noi la richiesta HTTP. */
public enum AiClient {
    SPRING_AI,
    REST_CLIENT
}
