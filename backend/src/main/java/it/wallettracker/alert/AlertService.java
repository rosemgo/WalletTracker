package it.wallettracker.alert;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.alert.AlertDto.AlertKind;
import it.wallettracker.alert.AlertDto.Severity;
import it.wallettracker.budget.BudgetDto;
import it.wallettracker.budget.BudgetDto.BudgetStatus;
import it.wallettracker.budget.BudgetService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gli avvisi: le cose che richiedono la tua attenzione.
 *
 * <p>Gli avvisi <b>non vengono salvati</b>: si calcolano ogni volta dallo stato attuale (scadenze dei
 * consensi, errori di sincronizzazione, budget). Così un avviso sparisce da solo appena il problema è
 * risolto, e non c'è niente da "segnare come letto" o da tenere allineato.
 *
 * <p>L'avviso più importante è il <b>consenso in scadenza</b>. Le regole PSD2 obbligano a rinnovarlo
 * (al massimo ogni 180 giorni) con un nuovo login sulla banca: WalletTracker non può farlo da solo, e
 * non deve. Senza avviso, un giorno i movimenti smetterebbero di arrivare senza che te ne accorga.
 */
@Service
public class AlertService {

    /** Da quanti giorni prima della scadenza avvisare. */
    static final long CONSENT_WARNING_DAYS = 14;

    private final BankConnectionRepository connectionRepository;
    private final AccountRepository accountRepository;
    private final BudgetService budgetService;

    public AlertService(BankConnectionRepository connectionRepository, AccountRepository accountRepository,
            BudgetService budgetService) {
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.budgetService = budgetService;
    }

    /** Gli avvisi di adesso, dal più urgente. */
    @Transactional(readOnly = true)
    public List<AlertDto> alerts() {
        return alerts(Instant.now());
    }

    /** Gli avvisi a un certo istante: il tempo è un parametro, così i test possono scegliere "adesso". */
    List<AlertDto> alerts(Instant now) {
        List<AlertDto> alerts = new ArrayList<>();

        // 1. I consensi scaduti o in scadenza, uno per banca. I giorni si contano sul calendario (dal 17 al
        //    23 ottobre sono 6 giorni, a qualsiasi ora), come li conta una persona, nel fuso orario del PC.
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.ofInstant(now, zone);
        for (BankConnection connection : connectionRepository.findAll()) {
            long daysLeft = ChronoUnit.DAYS.between(today, LocalDate.ofInstant(connection.getValidUntil(), zone));
            if (!connection.getValidUntil().isAfter(now)) {
                alerts.add(consentAlert(AlertKind.CONSENT_EXPIRED, Severity.CRITICAL, connection, daysLeft));
            } else if (daysLeft < CONSENT_WARNING_DAYS) {
                alerts.add(consentAlert(AlertKind.CONSENT_EXPIRING, Severity.WARNING, connection, daysLeft));
            }
        }

        // 2. Le sincronizzazioni fallite. Non per i conti con il consenso scaduto: lì il motivo lo
        //    sappiamo già (avviso 1), e due avvisi per lo stesso problema confondono.
        for (Account account : accountRepository.findAllWithConnection()) {
            if (account.getConnection() != null && account.getLastSyncError() != null
                    && account.getConnection().getValidUntil().isAfter(now)) {
                alerts.add(new AlertDto(AlertKind.SYNC_FAILED, Severity.WARNING,
                        account.getConnection().getAspspName(), account.getConnection().getAspspCountry(),
                        account.label(), null, null, List.of(), account.getLastSyncError()));
            }
        }

        // 3. I budget superati nel mese in corso: un solo avviso con tutte le categorie.
        List<String> overBudget = budgetService.budgets(YearMonth.now()).stream()
                .filter(budget -> budget.status() == BudgetStatus.OVER)
                .map(BudgetDto::category)
                .toList();
        if (!overBudget.isEmpty()) {
            alerts.add(new AlertDto(AlertKind.BUDGET_OVER, Severity.WARNING, null, null, null, null, null,
                    overBudget, null));
        }

        // Dal più urgente; a parità, prima la scadenza più vicina. sort() mantiene l'ordine di arrivo
        // per gli avvisi uguali ("ordinamento stabile").
        alerts.sort(Comparator.comparing(AlertDto::severity)
                .thenComparing(AlertDto::daysLeft, Comparator.nullsLast(Comparator.naturalOrder())));
        return alerts;
    }

    private static AlertDto consentAlert(AlertKind kind, Severity severity, BankConnection connection, long daysLeft) {
        return new AlertDto(kind, severity, connection.getAspspName(), connection.getAspspCountry(), null,
                connection.getValidUntil(), daysLeft, List.of(), null);
    }
}
