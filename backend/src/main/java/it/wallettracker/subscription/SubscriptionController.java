package it.wallettracker.subscription;

import java.time.LocalDate;
import java.util.List;

import it.wallettracker.subscription.SubscriptionDtos.ConfirmRequest;
import it.wallettracker.subscription.SubscriptionDtos.DismissRequest;
import it.wallettracker.subscription.SubscriptionDtos.Overview;
import it.wallettracker.subscription.SubscriptionDtos.Suggestion;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le API degli abbonamenti.
 * <ul>
 *   <li>{@code GET    /api/subscriptions}              abbonamenti confermati, totali, esercenti ignorati</li>
 *   <li>{@code GET    /api/subscriptions/suggestions}  i suggerimenti</li>
 *   <li>{@code POST   /api/subscriptions}              "sì, è un abbonamento" (201)</li>
 *   <li>{@code POST   /api/subscriptions/dismissed}    "no, non suggerirlo più" (201)</li>
 *   <li>{@code DELETE /api/subscriptions/{id}}         cancella una decisione (204)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    public Overview overview() {
        return subscriptionService.overview(LocalDate.now());
    }

    @GetMapping("/suggestions")
    public List<Suggestion> suggestions() {
        return subscriptionService.suggestions(LocalDate.now());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void confirm(@Valid @RequestBody ConfirmRequest request) {
        subscriptionService.confirm(request);
    }

    @PostMapping("/dismissed")
    @ResponseStatus(HttpStatus.CREATED)
    public void dismiss(@Valid @RequestBody DismissRequest request) {
        subscriptionService.dismiss(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        subscriptionService.delete(id);
    }
}
