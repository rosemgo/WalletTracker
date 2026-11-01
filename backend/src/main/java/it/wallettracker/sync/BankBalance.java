package it.wallettracker.sync;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import it.wallettracker.bank.enablebanking.EnableBankingApi.Balance;

/**
 * Quale saldo mostrare, tra quelli che la banca restituisce (docs/saldi.md, parte B). Una funzione "pura": riceve
 * la risposta di Enable Banking e sceglie, senza database.
 *
 * <p>Una banca può mandare più saldi dello stesso conto, ognuno con un tipo ISO 20022:
 * <ul>
 *   <li><b>contabili</b> ("booked"): {@code ITBD} (in giornata), {@code CLBD} (a fine giornata). Contano solo i
 *       movimenti già contabilizzati: è il "saldo contabile" dell'app della banca, e quello che si confronta con i
 *       movimenti;</li>
 *   <li><b>previsto</b>: {@code XPCD}, contabile più i movimenti in attesa;</li>
 *   <li><b>disponibili</b>: {@code ITAV}, {@code CLAV}, {@code FWAV}; possono comprendere un fido, quindi non sono
 *       i soldi del conto.</li>
 * </ul>
 * Si sceglie il primo gruppo che c'è, in quest'ordine; dentro il gruppo, il saldo più recente (a parità di giorno,
 * l'ordine dei tipi qui sopra). Solo saldi nella valuta del conto.
 */
public final class BankBalance {

    /** I gruppi, dal preferito. Un tipo che non è qui (OPBD, OTHR, PRCD...) si usa solo se non c'è nient'altro. */
    static final List<List<String>> PREFERENCE = List.of(
            List.of("ITBD", "CLBD"),
            List.of("XPCD"),
            List.of("ITAV", "CLAV", "FWAV"));

    private BankBalance() {
    }

    /** Il saldo scelto: importo e giorno. */
    public record Chosen(BigDecimal amount, LocalDate date, String type) {
    }

    /**
     * Sceglie il saldo da mostrare, o niente se la banca non ne dà uno utilizzabile.
     *
     * @param today il giorno da usare quando la banca non dice a quale giorno si riferisce il saldo
     */
    public static Optional<Chosen> choose(List<Balance> balances, String currency, LocalDate today) {
        if (balances == null) {
            return Optional.empty();
        }
        List<Chosen> usable = balances.stream()
                .filter(b -> b != null && b.balanceAmount() != null && b.balanceAmount().amount() != null)
                .filter(b -> b.balanceAmount().currency() == null
                        || b.balanceAmount().currency().equalsIgnoreCase(currency))
                .map(b -> new Chosen(b.balanceAmount().amount(), b.referenceDate() != null ? b.referenceDate() : today,
                        b.balanceType() == null ? "" : b.balanceType().toUpperCase(Locale.ROOT)))
                .toList();
        for (List<String> group : PREFERENCE) {
            Optional<Chosen> best = usable.stream()
                    .filter(c -> group.contains(c.type()))
                    .max(Comparator.comparing(Chosen::date)
                            .thenComparing(c -> -group.indexOf(c.type())));
            if (best.isPresent()) {
                return best;
            }
        }
        return usable.stream().max(Comparator.comparing(Chosen::date));
    }
}
