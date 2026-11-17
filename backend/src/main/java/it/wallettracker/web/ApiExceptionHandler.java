package it.wallettracker.web;

import it.wallettracker.message.ApiException;
import it.wallettracker.message.Message;
import it.wallettracker.message.Messages;
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

    private final Messages messages;

    public ApiExceptionHandler(Messages messages) {
        this.messages = messages;
    }

    /**
     * I nostri errori ({@link ApiException}): qui il codice diventa la frase, nella lingua della richiesta. Nel corpo
     * c'è anche il codice ({@code "code": "category.duplicate"}), per chi volesse riconoscere l'errore senza leggerne
     * le parole.
     */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail apiError(ApiException e) {
        return problem(e.getStatusCode(), e.message());
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ProblemDetail enableBankingError(RestClientResponseException e) {
        // messaggio: Enable Banking ha risposto con un errore: HTTP {0} {1}
        return problem(HttpStatus.BAD_GATEWAY, Message.of("enable-banking.error", e.getStatusCode().value(),
                e.getResponseBodyAsString()));
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail enableBankingUnreachable(RestClientException e) {
        // messaggio: Enable Banking non è raggiungibile: {0}
        return problem(HttpStatus.BAD_GATEWAY, Message.of("enable-banking.unreachable",
                String.valueOf(e.getMessage())));
    }

    private ProblemDetail problem(HttpStatusCode status, Message message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, messages.text(message));
        problem.setProperty("code", message.code());
        return problem;
    }

    /**
     * Un file caricato troppo grande (oltre {@code spring.servlet.multipart.max-file-size}): 413, con un
     * messaggio in italiano al posto di "Maximum upload size exceeded".
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // messaggio: Il file è troppo grande: al massimo 5 MB. Esporta un periodo più breve.
        ProblemDetail problem = problem(status, Message.of("upload.too-large"));
        return handleExceptionInternal(e, problem, headers, status, request);
    }
}
