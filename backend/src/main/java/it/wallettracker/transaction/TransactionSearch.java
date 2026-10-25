package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import it.wallettracker.category.Category;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * La ricerca nell'elenco dei movimenti come <b>Specification</b> di Spring Data JPA (docs/32, parte G).
 *
 * <p>Prima era una {@code @Query} fissa: andava bene finché i filtri erano sempre gli stessi. Con la ricerca per più
 * parole il numero di condizioni cambia a ogni richiesta ("netflix" una, "netflix amsterdam" due), e una query
 * scritta una volta per tutte non lo può fare. Una Specification invece è una condizione <b>costruita in Java</b>, con
 * il Criteria API di JPA: aggiungiamo un pezzo per ogni filtro acceso e per ogni parola, e Hibernate ne ricava l'SQL.
 *
 * <p>Ogni parola deve esserci (AND), in un campo qualsiasi (OR): "netflix amsterdam" trova "NETFLIX.COM AMSTERDAM",
 * e anche "amsterdam netflix"; "esselunga spesa" trova la causale "ESSELUNGA" nella categoria "Spesa alimentare".
 * Una parola che è un importo ("12,99") trova anche i movimenti con quell'importo, con i due segni.
 *
 * <p>Il testo arriva sempre come <b>parametro</b> ({@code cb.like} con un valore): niente SQL injection. Funzioni
 * pure di costruzione, provate da TransactionControllerTest.
 */
public final class TransactionSearch {

    /** Le parole al massimo: più di così non servono a trovare un movimento, e allungano solo la query. */
    static final int MAX_WORDS = 10;

    /** Un importo scritto all'italiana o all'inglese: "12,99", "12.99", "1.234,56", "1234", "-45,50". */
    private static final Pattern AMOUNT = Pattern.compile(
            "-?(\\d{1,3}(\\.\\d{3})+(,\\d{1,2})?|\\d+([.,]\\d{1,2})?)");

    private TransactionSearch() {
    }

    /**
     * Le parole del testo cercato: in minuscolo, divise dagli spazi. "-45,50 €" resta un importo: il simbolo dell'euro
     * staccato si ignora, attaccato ("45,50€") si toglie.
     */
    static List<String> words(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).strip().split("\\s+"))
                .map(word -> word.endsWith("€") && word.length() > 1 ? word.substring(0, word.length() - 1) : word)
                .filter(word -> !word.equals("€"))
                .toList();
    }

    /**
     * La condizione dell'elenco: i filtri accesi, e ogni parola della ricerca. I movimenti escono dal più recente
     * (l'ordine è nella richiesta di pagina, vedi TransactionService).
     */
    static Specification<BankTransaction> of(TransactionFilter filter, List<String> words) {
        return (root, query, cb) -> {
            // La categoria serve due volte: per mostrarne il nome (caricata insieme al movimento, senza una query in
            // più per ognuno: il problema N+1, docs/09) e per cercarci dentro. Nella query di conteggio delle pagine
            // (risultato Long) non si può "caricare" niente: lì basta un join semplice.
            Join<BankTransaction, Category> category;
            if (Long.class.equals(query.getResultType())) {
                category = root.join("category", JoinType.LEFT);
            } else {
                // Con Hibernate un "fetch" è anche un join: lo si può usare per le condizioni, come c.name.
                Fetch<BankTransaction, Category> fetched = root.fetch("category", JoinType.LEFT);
                category = (Join<BankTransaction, Category>) fetched;
                root.fetch("manualCategory", JoinType.LEFT);
            }

            List<Predicate> conditions = new ArrayList<>();
            if (filter.accountId() != null) {
                conditions.add(cb.equal(root.get("account").get("id"), filter.accountId()));
            }
            if (filter.type() != null) {
                conditions.add(cb.equal(root.get("type"), filter.type()));
            }
            if (filter.uncategorized()) {
                conditions.add(cb.isNull(root.get("category")));
            }
            if (filter.from() != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.get("bookingDate"), filter.from()));
            }
            if (filter.to() != null) {
                conditions.add(cb.lessThanOrEqualTo(root.get("bookingDate"), filter.to()));
            }
            for (String word : words) {
                conditions.add(wordAnywhere(cb, word, root.get("description"), root.get("counterparty"),
                        category.get("name"), root.get("amount")));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    /**
     * Una parola, in un campo qualsiasi: causale, controparte, nome della categoria; e, se è un importo, l'importo.
     * {@code lower(coalesce(campo, ''))}: senza badare a maiuscole e minuscole, e un campo vuoto (null) è testo vuoto
     * (altrimenti {@code null LIKE ...} non sarebbe né vero né falso).
     */
    private static Predicate wordAnywhere(CriteriaBuilder cb, String word, Expression<String> description,
            Expression<String> counterparty, Expression<String> categoryName, Expression<BigDecimal> amount) {
        String pattern = likePattern(word);
        List<Predicate> anyField = new ArrayList<>();
        for (Expression<String> field : List.of(description, counterparty, categoryName)) {
            anyField.add(cb.like(cb.lower(cb.coalesce(field, "")), pattern, '\\'));
        }
        BigDecimal value = amountIn(word);
        if (value != null) {
            anyField.add(cb.equal(amount, value));
            anyField.add(cb.equal(amount, value.negate()));
        }
        return cb.or(anyField.toArray(Predicate[]::new));
    }

    /**
     * Una parola come modello per LIKE: tra due {@code %} ("contiene"). I caratteri che per LIKE hanno un significato
     * ({@code %} "qualsiasi testo", {@code _} "un carattere qualsiasi") e il carattere di escape {@code \} si
     * proteggono con un {@code \} davanti: cercando "100%" si trova "100%", non "100" seguito da qualsiasi cosa.
     */
    static String likePattern(String word) {
        String escaped = word.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * Se la parola è un importo, il suo valore (senza segno: la ricerca trova sia le spese sia le entrate);
     * altrimenti null. "1.234" è milleduecentotrentaquattro: un importo ha due decimali, non tre.
     */
    static BigDecimal amountIn(String word) {
        String cleaned = word.replace("€", "").replace(" ", "");
        if (!AMOUNT.matcher(cleaned).matches()) {
            return null;
        }
        String number = cleaned.contains(",") || cleaned.matches("-?\\d{1,3}(\\.\\d{3})+")
                ? cleaned.replace(".", "").replace(",", ".")
                : cleaned;
        return new BigDecimal(number).abs();
    }
}
