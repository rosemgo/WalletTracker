package it.wallettracker.suggestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import it.wallettracker.suggestion.HistorySuggester.AiAnswer;
import it.wallettracker.suggestion.HistorySuggester.Candidate;
import it.wallettracker.suggestion.HistorySuggester.Dismissal;
import it.wallettracker.suggestion.HistorySuggester.Example;
import it.wallettracker.suggestion.HistorySuggester.Suggestion;
import it.wallettracker.suggestion.HistorySuggester.UsedCategory;
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
                new Suggestion("PIZZERIA DA GINO", "Pizzeria da Gino", 5L, "Ristoranti e bar", 2, 1, null));
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

    @Test
    void aMerchantYouNeverChoseGetsTheAnswerOfTheAi() {
        List<Suggestion> suggestions = HistorySuggester.suggest(List.of(),
                List.of(candidate("CONAD SUPERSTORE", "CONAD SUPERSTORE 4521", "2026-10-12")), Set.of(),
                List.of(new AiAnswer("CONAD SUPERSTORE", 1L, "Spesa alimentare", "gemma3:4b")));

        assertThat(suggestions).containsExactly(
                new Suggestion("CONAD SUPERSTORE", "CONAD SUPERSTORE", 1L, "Spesa alimentare", 1, 0, "gemma3:4b"));
    }

    @Test
    void yourHistoryWinsOverTheAi() {
        // Hai scelto tu la pizzeria: la risposta dell'IA (chiesta prima) non conta più.
        List<AiAnswer> ai = List.of(new AiAnswer("PIZZERIA DA GINO", 9L, "Lavoro", "gemma3:4b"));
        List<Candidate> candidates = List.of(candidate("PIZZERIA DA GINO", "Gino", "2026-10-12"));

        assertThat(HistorySuggester.suggest(List.of(PIZZA_IS_RESTAURANT), candidates, Set.of(), ai))
                .extracting(Suggestion::category, Suggestion::model)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Ristoranti e bar", null));
        // E con scelte diverse (Amazon), nessun suggerimento: nemmeno dall'IA.
        List<Example> amazon = List.of(new Example("AMAZON", 1L, "Spesa alimentare"), new Example("AMAZON", 2L, "Shopping"));
        assertThat(HistorySuggester.suggest(amazon, List.of(candidate("AMAZON", "AMAZON", "2026-10-12")), Set.of(),
                List.of(new AiAnswer("AMAZON", 2L, "Shopping", "gemma3:4b")))).isEmpty();
    }

    @Test
    void aNoToTheAiIsNotProposedAgain() {
        assertThat(HistorySuggester.suggest(List.of(), List.of(candidate("BAR CENTRALE", "Bar", "2026-10-12")),
                Set.of(new Dismissal("BAR CENTRALE", 5L)),
                List.of(new AiAnswer("BAR CENTRALE", 5L, "Ristoranti e bar", "gemma3:4b")))).isEmpty();
    }

    @Test
    void historyFirstThenTheAi() {
        List<Candidate> candidates = List.of(candidate("PIZZERIA DA GINO", "Gino", "2026-10-12"),
                candidate("CONAD", "Conad", "2026-10-12"), candidate("CONAD", "Conad", "2026-10-13"));
        List<AiAnswer> ai = List.of(new AiAnswer("CONAD", 1L, "Spesa alimentare", "gemma3:4b"));

        // CONAD ha più movimenti, ma il suggerimento dalla tua storia viene prima.
        assertThat(HistorySuggester.suggest(List.of(PIZZA_IS_RESTAURANT), candidates, Set.of(), ai))
                .extracting(Suggestion::merchantKey).containsExactly("PIZZERIA DA GINO", "CONAD");
    }

    @Test
    void anAnswerForAMerchantWithoutMovementsToCategoriseIsNotShown() {
        assertThat(HistorySuggester.suggest(List.of(), List.of(), Set.of(),
                List.of(new AiAnswer("CONAD", 1L, "Spesa alimentare", "gemma3:4b")))).isEmpty();
    }

    @Test
    void usedCategoriesListsEveryCategoryOfTheMerchantMostUsedFirst() {
        // Per un movimento solo, anche le scelte diverse vanno bene: le mettiamo in cima al menu.
        List<Example> examples = List.of(new Example("AMAZON", 1L, "Spesa alimentare"),
                new Example("AMAZON", 2L, "Shopping"), new Example("AMAZON", 2L, "Shopping"),
                new Example("AMAZON", 3L, "Casa"), PIZZA_IS_RESTAURANT);

        assertThat(HistorySuggester.usedCategories(examples, "AMAZON")).containsExactly(
                new UsedCategory(2L, "Shopping", 2),
                new UsedCategory(3L, "Casa", 1),               // a parità, in ordine alfabetico
                new UsedCategory(1L, "Spesa alimentare", 1));
    }

    @Test
    void usedCategoriesIsEmptyForANewMerchantOrAMovementWithoutText() {
        assertThat(HistorySuggester.usedCategories(List.of(PIZZA_IS_RESTAURANT), "BAR CENTRALE")).isEmpty();
        assertThat(HistorySuggester.usedCategories(List.of(PIZZA_IS_RESTAURANT), null)).isEmpty();
    }

    private static Candidate candidate(String key, String text, String date) {
        return new Candidate(key, text, LocalDate.parse(date));
    }
}
