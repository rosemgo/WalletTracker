package it.wallettracker.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Trasforma gli errori di Enable Banking in risposte comprensibili per la dashboard.
 *
 * <p>{@code @RestControllerAdvice}: i metodi {@code @ExceptionHandler} di questa classe valgono per TUTTI i
 * controller. Quando un controller lancia una delle eccezioni elencate, Spring chiama il metodo
 * corrispondente invece di rispondere con un generico 500.
 *
 * <p>Rispondiamo 502 Bad Gateway: è il codice HTTP per "il server che sta dietro di me (qui Enable Banking)
 * ha risposto con un errore".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RestClientResponseException.class)
    public ProblemDetail enableBankingError(RestClientResponseException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Enable Banking ha risposto con un errore: HTTP " + e.getStatusCode().value() + " "
                        + e.getResponseBodyAsString());
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail enableBankingUnreachable(RestClientException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Enable Banking non è raggiungibile: " + e.getMessage());
    }
}
