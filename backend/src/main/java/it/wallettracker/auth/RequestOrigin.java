package it.wallettracker.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Da dove arriva una richiesta: dal PC su cui gira l'app, o da un altro dispositivo (docs/46, parte C).
 *
 * <p>Non lo si può capire dal nome scritto nella richiesta (l'header {@code Host}): lo sceglie chi la manda, e un
 * dispositivo della rete Tailscale potrebbe scrivere "localhost". Lo si capisce invece dalla <b>porta</b> da cui è
 * entrata in nginx:
 * <ul>
 *   <li>la porta 8000 (127.0.0.1:8000) la raggiunge solo chi è sul PC;</li>
 *   <li>la porta 8001 è quella che userà Tailscale (passo 4) per i dispositivi della tua rete.</li>
 * </ul>
 * nginx scrive nell'header {@value #HEADER} "local" o "remote" a seconda della porta, e <b>sostituisce</b> quello
 * che avesse scritto il browser: un dispositivo che mandasse "local" da solo non cambierebbe niente. In sviluppo
 * l'header lo mettono il proxy di {@code ng serve} (proxy.conf.json) e {@code scripts/serve-dist.mjs}, che girano sul
 * PC.
 *
 * <p>Senza header la richiesta conta come "da un altro dispositivo": nel dubbio, la scelta più prudente.
 */
public final class RequestOrigin {

    public static final String HEADER = "X-WalletTracker-Access";

    private RequestOrigin() {
    }

    public static boolean isLocal(HttpServletRequest request) {
        return "local".equals(request.getHeader(HEADER));
    }
}
