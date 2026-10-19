package it.wallettracker.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Trasforma gli errori in risposte comprensibili per la dashboard: quelli di Enable Banking e quelli
 * standard di Spring (vedi sotto).
 *
 * <p>{@code @RestControllerAdvice}: i metodi {@code @ExceptionHandler} di questa classe valgono per TUTTI i
 * controller. Quando un controller lancia una delle eccezioni elencate, Spring chiama il metodo
 * corrispondente invece di rispondere con un generico 500.
 *
 * <p>Rispondiamo 502 Bad Gateway: è il codice HTTP per "il server che sta dietro di me (qui Enable Banking)
 * ha risposto con un errore".
 *
 * <p>Estende {@link ResponseEntityExceptionHandler}, la classe di Spring che trasforma gli errori standard
 * (400 di validazione, 404, 405...) in Problem Details. Con {@code spring.mvc.problemdetails.enabled}
 * Spring Boot ne registra una sua copia, ma solo se non ce n'è già una: estendendola noi, c'è solo
 * questa, e possiamo cambiare il messaggio di uno di quegli errori ({@link #handleMaxUploadSizeExceededException}).
 * Se avessimo scritto un {@code @ExceptionHandler} per lo stesso errore, avrebbe vinto la copia di Boot,
 * che viene consultata per prima.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

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

    /**
     * Un file caricato troppo grande (oltre {@code spring.servlet.multipart.max-file-size}): 413, con un
     * messaggio in italiano al posto di "Maximum upload size exceeded".
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "Il file è troppo grande: al massimo 5 MB. Esporta un periodo più breve.");
        return handleExceptionInternal(e, problem, headers, status, request);
    }
}
