package it.wallettracker.suggestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import it.wallettracker.suggestion.HistorySuggester.Candidate;
import it.wallettracker.suggestion.HistorySuggester.Dismissal;
import it.wallettracker.suggestion.HistorySuggester.Example;
import it.wallettracker.suggestion.HistorySuggester.Suggestion;
import org.junit.jupiter.api.Test;

/** Test "puri" dei suggerimenti dalla storia: niente Spring, niente database. */
class HistorySuggesterTest {

    private static final Example PIZZA_IS_RESTAURANT = new Example("PIZZERIA DA GINO", 5L, "Ristoranti e bar");

    @Test
    void anotherMovementOfTheSameMerchantGetsTheCategoryYouChose() {
        List<Suggestion> suggestions = HistorySuggester.suggest(List.of(PIZZA_IS_RESTAURANT),
                List.of(candidate("PIZZERIA DA GINO", "Pizzeria da Gino 12/10", "2026-10-12"),
                        candidate("PIZZERIA DA GINO", "Pizzeria da Gino 19/10", "2026-10-19")),
                Set.of());

        assertThat(suggestions).containsExactly(
                new Suggestion("PIZZERIA DA GINO", "Pizzeria da Gino", 5L, "Ristoranti e bar", 2, 1));
    }

    @Test
    void differentChoicesForTheSameMerchantMeanNoSuggestion() {
        // Amazon: una volta spesa alimentare, una volta elettronica. Non tocca al programma scegliere.
        List<Example> examples = List.of(new Example("AMAZON", 1L, "Spesa alimentare"),
                new Example("AMAZON", 2L, "Elettronica"));

        assertThat(HistorySuggester.suggest(examples, List.of(candidate("AMAZON", "AMAZON", "2026-10-12")), Set.of()))
                .isEmpty();
    }

    @Test
    void theSameChoiceSeveralTimesCountsTheExamples() {
        List<Example> examples = List.of(PIZZA_IS_RESTAURANT, PIZZA_IS_RESTAURANT, PIZZA_IS_RESTAURANT);

        assertThat(HistorySuggester.suggest(examples, List.of(candidate("PIZZERIA DA GINO", "x", "2026-10-12")),
                Set.of())).singleElement().extracting(Suggestion::examples).isEqualTo(3);
    }

    @Test
    void aDismissedSuggestionDoesNotComeBackButAnotherCategoryWould() {
        List<Candidate> candidates = List.of(candidate("PIZZERIA DA GINO", "x", "2026-10-12"));

        assertThat(HistorySuggester.suggest(List.of(PIZZA_IS_RESTAURANT), candidates,
                Set.of(new Dismissal("PIZZERIA DA GINO", 5L)))).isEmpty();
        // Il "no" era per "Ristoranti e bar": se poi scegli un'altra categoria, quella si può suggerire.
        assertThat(HistorySuggester.suggest(List.of(new Example("PIZZERIA DA GINO", 9L, "Lavoro")), candidates,
                Set.of(new Dismissal("PIZZERIA DA GINO", 5L)))).hasSize(1);
    }

    @Test
    void merchantsYouNeverCategorisedHaveNoSuggestion() {
        assertThat(HistorySuggester.suggest(List.of(PIZZA_IS_RESTAURANT),
                List.of(candidate("BAR CENTRALE", "BAR CENTRALE", "2026-10-12")), Set.of())).isEmpty();
    }

    @Test
    void theMerchantsWithMoreMovementsComeFirst() {
        List<Example> examples = List.of(PIZZA_IS_RESTAURANT, new Example("BAR CENTRALE", 5L, "Ristoranti e bar"));
        List<Candidate> candidates = List.of(candidate("PIZZERIA DA GINO", "Gino", "2026-10-12"),
                candidate("BAR CENTRALE", "Bar", "2026-10-12"), candidate("BAR CENTRALE", "Bar", "2026-10-13"));

        assertThat(HistorySuggester.suggest(examples, candidates, Set.of()))
                .extracting(Suggestion::merchantKey).containsExactly("BAR CENTRALE", "PIZZERIA DA GINO");
    }

    private static Candidate candidate(String key, String text, String date) {
        return new Candidate(key, text, LocalDate.parse(date));
    }
}
