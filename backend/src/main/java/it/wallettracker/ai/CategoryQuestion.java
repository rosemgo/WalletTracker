package it.wallettracker.ai;

import java.util.List;

/**
 * La domanda per il modello: "di che categoria è questo esercente?".
 *
 * @param merchant   il testo dell'esercente, come lo scrive la banca (es. "MAR GROUP SPA FOGLIANISE ITA")
 * @param expense    true se è una spesa, false se è un'entrata
 * @param categories le categorie tra cui scegliere (solo quelle del gruppo giusto: di spesa o di entrata)
 * @param examples   alcune scelte fatte dall'utente, come esempio ("few-shot"); può essere vuoto
 */
public record CategoryQuestion(String merchant, boolean expense, List<Option> categories, List<Example> examples) {

    /** Una categoria tra cui scegliere: il numero (l'id nel database) e il nome. */
    public record Option(Long id, String name) {
    }

    /** Una scelta dell'utente, da mostrare al modello come esempio. */
    public record Example(String merchant, String category) {
    }
}
