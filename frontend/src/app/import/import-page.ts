import { Component, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, Subject, catchError, switchMap } from 'rxjs';

import {
  Account,
  AmountMode,
  DecimalSeparator,
  ImportMapping,
  ImportPreview,
  ImportResult,
} from '../api/models';
import { BalanceForm } from '../accounts/balance-form';
import { WalletApi } from '../api/wallet-api';
import { MoneyPipe } from '../privacy/money.pipe';
import { LocalDatePipe, formatLocalDate } from '../text/dates';
import { plural } from '../text/plural';
import { FileDrop } from '../ui/file-drop';
import { isTradeRepublic } from './trade-republic';

/** I separatori che il backend accetta, con un nome leggibile. */
export const DELIMITERS: { value: string; label: string }[] = [
  { value: ';', label: 'Punto e virgola ( ; )' },
  { value: ',', label: 'Virgola ( , )' },
  { value: '\t', label: 'Tabulazione' },
  { value: '|', label: 'Barra verticale ( | )' },
];

/** I formati di data del backend (RowReader.DATE_FORMATS), con un esempio. */
export const DATE_FORMATS: { value: string; label: string }[] = [
  { value: 'dd/MM/yyyy', label: '31/12/2026' },
  { value: 'dd-MM-yyyy', label: '31-12-2026' },
  { value: 'dd.MM.yyyy', label: '31.12.2026' },
  { value: 'yyyy-MM-dd', label: '2026-12-31' },
  { value: 'MM/dd/yyyy', label: "12/31/2026 (prima il mese, all'americana)" },
  { value: 'dd/MM/yy', label: '31/12/26' },
];

/** Oltre questa dimensione il backend rifiuta il file (spring.servlet.multipart.max-file-size). */
export const MAX_FILE_BYTES = 5 * 1024 * 1024;

/** "1 riga" oppure "75 righe": la forma per uno così com'è, quella per gli altri con il numero davanti. */
function countOf(count: number, one: string, many: string): string {
  return plural(count, { one, other: `${count} ${many}` });
}

/**
 * L'importazione dei movimenti da un file, CSV o Excel (docs/importa-file.md), in qualunque conto:
 * - un conto manuale (che Enable Banking non copre): si importano tutte le righe nuove;
 * - un conto collegato: i movimenti li porta la banca, ma solo da quando è stato collegato (lo storico che dà). Dal
 *   file si aggiungono solo le righe più vecchie: i mesi che la banca non ha mai dato.
 * Il comportamento lo decide il conto scelto, e la pagina lo dice prima di caricare il file.
 *
 * Il percorso:
 * 1. scegli il conto e il file: il backend legge il file e PROPONE come interpretare le colonne;
 * 2. controlli l'anteprima e, se serve, correggi la mappatura: ogni modifica chiede una nuova anteprima;
 * 3. quando tutte le righe si leggono, importi.
 *
 * Il file di Trade Republic il backend lo riconosce dalle colonne e lo legge da solo (preview.tradeRepublic): al posto
 * della mappatura la pagina spiega come lo legge (docs/importa-file.md, parte H).
 *
 * Il file resta nel browser: lo mandiamo al backend a ogni anteprima e all'importazione, che non lo
 * conserva.
 */
@Component({
  selector: 'app-import-page',
  imports: [BalanceForm, LocalDatePipe, FileDrop, MoneyPipe, ReactiveFormsModule, RouterLink],
  templateUrl: './import-page.html',
})
export class ImportPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly route = inject(ActivatedRoute);

  protected readonly delimiters = DELIMITERS;
  protected readonly dateFormats = DATE_FORMATS;

  protected readonly accounts = signal<Account[]>([]); // tutti: manuali e collegati
  /** I conti manuali e quelli collegati, per i due gruppi del menu. */
  protected readonly manualAccounts = computed(() =>
    this.accounts().filter((a) => a.source === 'MANUAL'),
  );
  protected readonly linkedAccounts = computed(() =>
    this.accounts().filter((a) => a.source !== 'MANUAL'),
  );
  /** Il conto scelto (il menu dà l'id; qui il conto intero). */
  protected readonly selectedId = signal<number | null>(null);
  protected readonly selected = computed(
    () => this.accounts().find((a) => a.id === this.selectedId()) ?? null,
  );
  /** Il conto scelto è collegato: si aggiungono solo i mesi più vecchi della banca. */
  protected readonly linked = computed(() => {
    const a = this.selected();
    return a !== null && a.source !== 'MANUAL';
  });
  /**
   * Un conto Trade Republic collegato: il suo file completa i movimenti arrivati dalla banca e carica quelli più vecchi
   * (docs/importa-file.md, parti I e J). La pagina lo dice prima di caricare il file.
   */
  protected readonly selectedIsTradeRepublic = computed(() => {
    const a = this.selected();
    return a !== null && a.source !== 'MANUAL' && isTradeRepublic(a);
  });
  protected readonly loading = signal(true);
  protected readonly file = signal<File | null>(null);
  protected readonly preview = signal<ImportPreview | null>(null);
  protected readonly result = signal<ImportResult | null>(null);
  /** Dopo l'importazione: il conto con il saldo aggiornato (docs/saldi.md, parte C). */
  protected readonly importedAccount = signal<Account | null>(null);
  /** Si sta scrivendo il saldo (subito, se il conto non ne ha ancora uno). */
  protected readonly editingBalance = signal(false);
  /** Il saldo appena salvato, per il messaggio. */
  protected readonly balanceSaved = signal(false);
  protected readonly importing = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Le colonne del file, per i menu della mappatura: "1 · Data", "2 · Descrizione"... */
  protected readonly columns = computed(() =>
    (this.preview()?.headers ?? []).map((header, index) => ({
      index,
      label: `${index + 1} · ${header.trim() === '' ? '(senza nome)' : header}`,
    })),
  );

  /**
   * Si può importare solo se ogni riga si legge con la mappatura scelta, e c'è qualcosa da fare: righe nuove, o righe
   * già importate da completare (un conto manuale, docs/tinaba.md, parte H).
   */
  protected readonly canImport = computed(() => {
    const preview = this.preview();
    return (
      preview !== null &&
      preview.errorCount === 0 &&
      preview.parsedRows.length > 0 &&
      (preview.newRows > 0 ||
        preview.completeRows > 0 ||
        (!preview.linkedAccount && preview.duplicateRows > 0)) &&
      !this.importing()
    );
  });

  protected readonly account = this.fb.control<number | null>(null);

  /**
   * La mappatura, un controllo per ogni campo di ImportMapping. Le colonne facoltative valgono null
   * ("nessuna"): per questo nei menu usiamo [ngValue], che conserva il numero o il null (con [value]
   * diventerebbero stringhe).
   */
  protected readonly mapping = this.fb.group({
    delimiter: [';'],
    dateColumn: this.fb.control<number | null>(null),
    dateFormat: ['dd/MM/yyyy'],
    amountMode: ['SINGLE' as AmountMode],
    amountColumn: this.fb.control<number | null>(null),
    incomeColumn: this.fb.control<number | null>(null),
    expenseColumn: this.fb.control<number | null>(null),
    decimalSeparator: ['COMMA' as DecimalSeparator],
    descriptionColumn: this.fb.control<number | null>(null),
    counterpartyColumn: this.fb.control<number | null>(null),
    currencyColumn: this.fb.control<number | null>(null),
    signColumn: this.fb.control<number | null>(null),
    incomeWords: this.fb.control<string | null>(null),
    detailColumn: this.fb.control<number | null>(null),
  });

  /**
   * Le richieste di anteprima passano da qui. switchMap annulla la richiesta precedente se ne parte una
   * nuova: se l'utente cambia due menu di fila, conta solo la risposta all'ultima modifica (senza, una
   * risposta vecchia arrivata tardi potrebbe sovrascrivere quella giusta).
   * Il valore è la mappatura da provare, o null per "proponila tu".
   */
  private readonly previewRequests = new Subject<ImportMapping | null>();

  constructor() {
    // takeUntilDestroyed: le iscrizioni si chiudono da sole quando si lascia la pagina.
    this.previewRequests
      .pipe(
        switchMap((mapping) =>
          this.api.previewImport(this.file()!, this.account.value!, mapping).pipe(
            catchError((response) => {
              this.preview.set(null);
              this.error.set(response.error?.detail ?? 'Il file non si può leggere.');
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((preview) => {
        this.error.set(null);
        this.preview.set(preview);
        // Riempiamo i menu con la mappatura usata (la prima volta è quella proposta dal backend).
        // emitEvent: false, altrimenti questo cambiamento chiederebbe un'altra anteprima, all'infinito.
        // patchValue invece di setValue: aggiorna i campi che arrivano e lascia gli altri.
        this.mapping.patchValue(preview.mapping, { emitEvent: false });
      });

    // Ogni modifica della mappatura chiede una nuova anteprima.
    this.mapping.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.requestPreview(this.mapping.getRawValue()));
    // Cambiando conto cambiano la valuta predefinita e che cosa si aggiunge (conto manuale o collegato): rifacciamo
    // l'anteprima con la stessa mappatura.
    this.account.valueChanges.pipe(takeUntilDestroyed()).subscribe((id) => {
      this.selectedId.set(id);
      this.requestPreview(this.preview() ? this.mapping.getRawValue() : null);
    });
  }

  ngOnInit(): void {
    // ?conto=5 (il link "importa un file" della pagina Conti) sceglie già il conto.
    const fromLink = Number(this.route.snapshot.queryParamMap.get('conto'));
    this.api.accounts().subscribe({
      next: (accounts) => {
        // Prima i conti manuali (l'uso più comune di un file), poi quelli collegati.
        this.accounts.set(accounts);
        const ordered = [...this.manualAccounts(), ...this.linkedAccounts()];
        const chosen = ordered.find((a) => a.id === fromLink) ?? ordered[0];
        this.account.setValue(chosen?.id ?? null, { emitEvent: false });
        this.selectedId.set(chosen?.id ?? null);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossibile caricare i conti: il backend è avviato?');
        this.loading.set(false);
      },
    });
  }

  /** L'utente ha scelto un file: chiediamo l'anteprima senza mappatura, così il backend ne propone una. */
  onFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    this.preview.set(null);
    this.error.set(null);
    if (file && file.size > MAX_FILE_BYTES) {
      // Lo diciamo subito, senza mandarlo: il backend lo rifiuterebbe comunque (413).
      this.file.set(null);
      this.error.set('Il file è troppo grande: al massimo 5 MB. Esporta un periodo più breve.');
      return;
    }
    this.file.set(file);
    this.requestPreview(null);
  }

  importFile(): void {
    const file = this.file();
    const accountId = this.account.value;
    if (!file || accountId === null || !this.canImport()) {
      return;
    }
    this.importing.set(true);
    this.error.set(null);
    this.api.importCsv(file, accountId, this.mapping.getRawValue()).subscribe({
      next: (result) => {
        this.importing.set(false);
        this.result.set(result);
        // Il saldo si scrive solo per un conto manuale: quello di un conto collegato lo dà la banca.
        if (!this.linked()) {
          this.loadImportedAccount(accountId);
        }
      },
      error: (response) => {
        this.importing.set(false);
        // Con righe sbagliate il backend manda anche l'elenco (campo "errors" del ProblemDetail).
        const errors: string[] = response.error?.errors ?? [];
        const detail = response.error?.detail ?? "L'importazione non è riuscita.";
        this.error.set([detail, ...errors].join(' '));
      },
    });
  }

  /**
   * Il conto appena importato, con il saldo aggiornato dai movimenti nuovi. Se non ha ancora un saldo, si chiede
   * subito; altrimenti si mostra, da confrontare con la banca.
   */
  private loadImportedAccount(accountId: number): void {
    this.balanceSaved.set(false);
    this.api.accounts().subscribe({
      next: (accounts) => {
        const account = accounts.find((a) => a.id === accountId) ?? null;
        this.importedAccount.set(account);
        this.editingBalance.set(account !== null && account.balance === null);
      },
      error: () => this.importedAccount.set(null), // il saldo è un di più: l'importazione è fatta
    });
  }

  onBalanceSaved(account: Account): void {
    this.importedAccount.set(account);
    this.editingBalance.set(false);
    this.balanceSaved.set(true);
  }

  /** "Importa un altro file": si ricomincia, con lo stesso conto. */
  restart(): void {
    this.importedAccount.set(null);
    this.editingBalance.set(false);
    this.result.set(null);
    this.preview.set(null);
    this.file.set(null);
    this.error.set(null);
  }

  /** "4 movimenti importati (2 righe erano già presenti…); a 1 movimento … la descrizione." */
  protected doneMessage(done: ImportResult): string {
    if (this.linked() && this.preview()?.tradeRepublic) {
      return this.tradeRepublicDoneMessage(done);
    }
    let text = this.linked()
      ? plural(done.imported, {
          one: '1 movimento aggiunto',
          other: `${done.imported} movimenti aggiunti`,
        })
      : plural(done.imported, {
          one: '1 movimento importato',
          other: `${done.imported} movimenti importati`,
        });
    if (done.covered > 0) {
      text += plural(done.covered, {
        one: '; 1 riga era nel periodo che porta la banca ed è stata saltata',
        other: `; ${done.covered} righe erano nel periodo che porta la banca e sono state saltate`,
      });
    }
    if (done.duplicates > 0) {
      text += plural(done.duplicates, {
        one: ' (1 riga era già presente ed è stata saltata)',
        other: ` (${done.duplicates} righe erano già presenti e sono state saltate)`,
      });
    }
    if (done.completed > 0) {
      // Righe importate prima che si leggesse la seconda colonna della descrizione (docs/tinaba.md, parte H).
      text += plural(done.completed, {
        one: '; a 1 movimento già presente è stata completata la descrizione',
        other: `; a ${done.completed} movimenti già presenti è stata completata la descrizione`,
      });
    }
    return `${text}.`;
  }

  /**
   * Il file di Trade Republic in un conto collegato: "4 movimenti completati con causale e controparte; nessun
   * movimento caricato; 81 righe erano già presenti."
   */
  private tradeRepublicDoneMessage(done: ImportResult): string {
    const n = countOf;
    const parts = [
      done.completed === 0
        ? 'nessun movimento da completare'
        : n(done.completed, '1 movimento completato', 'movimenti completati') +
          ' con causale e controparte',
      done.imported === 0
        ? 'nessun movimento caricato'
        : n(done.imported, '1 movimento caricato', 'movimenti caricati'),
    ];
    if (done.duplicates > 0) {
      parts.push(n(done.duplicates, '1 riga era già presente', 'righe erano già presenti'));
    }
    const text = parts.join('; ');
    return `${text.charAt(0).toUpperCase()}${text.slice(1)}.`;
  }

  protected accountName(): string {
    return this.selected()?.label ?? '';
  }

  private readonly locale = inject(LOCALE_ID);

  /** " (il 22 luglio 2026)": il primo giorno della banca, quando l'anteprima lo sa. */
  protected bankFromText(): string {
    const from = this.preview()?.bankFrom;
    return from ? ` (il ${formatLocalDate(from, 'long', this.locale)})` : '';
  }

  /**
   * Che cosa succederà, in una frase. Conto collegato: "Il file ha 75 righe: 43 movimenti più vecchi del 22 luglio 2026
   * si aggiungono; 32 sono nel periodo che porta la banca." Conto manuale: "75 righe nuove, 3 già importate…".
   */
  protected summary(p: ImportPreview): string {
    const n = countOf;
    // Le operazioni sui titoli del file di Trade Republic: nel file, ma senza denaro sul conto.
    const skipped =
      p.skippedRows > 0
        ? `; ${n(
            p.skippedRows,
            '1 operazione su titoli senza movimento di denaro non viene registrata',
            'operazioni su titoli senza movimento di denaro non vengono registrate',
          )}`
        : '';
    if (!p.linkedAccount) {
      let text = n(p.newRows, '1 riga nuova', 'righe nuove');
      if (p.duplicateRows > 0) {
        text += `, ${n(p.duplicateRows, '1 già importata, che si salta', 'già importate, che si saltano')}`;
      }
      return `${text}${skipped}.`;
    }
    const from = p.bankFrom ? formatLocalDate(p.bankFrom, 'long', this.locale) : null;
    if (p.tradeRepublic) {
      return this.tradeRepublicSummary(p, from ?? '');
    }
    const added =
      p.newRows === 0
        ? 'nessun movimento da aggiungere'
        : `${n(p.newRows, '1 movimento più vecchio', 'movimenti più vecchi')} del ${from} ` +
          plural(p.newRows, { one: 'si aggiunge', other: 'si aggiungono' });
    let text = `Il file ha ${n(p.totalRows, '1 riga', 'righe')}: ${added}`;
    if (p.coveredRows > 0) {
      text += `; ${n(p.coveredRows, '1 è nel periodo che porta la banca', 'sono nel periodo che porta la banca')}`;
    }
    if (p.duplicateRows > 0) {
      text += `; ${n(p.duplicateRows, '1 era già stata aggiunta', 'erano già state aggiunte')}`;
    }
    text += `${skipped}.`;
    if (p.newRows === 0) {
      text +=
        p.duplicateRows > 0
          ? ' I mesi più vecchi di questo file li hai già aggiunti; gli altri li porta la banca.'
          : ` La banca ha già tutti i mesi di questo file: dal ${from} in poi li porta lei.`;
    }
    return text;
  }

  /**
   * Il file di Trade Republic in un conto collegato, che completa e carica (docs/importa-file.md, parti I e J): "Il file
   * contiene 92 righe: 4 movimenti ricevuti dalla banca verranno completati con causale e controparte; nessun movimento
   * precedente al 9 luglio 2026 da caricare; 81 righe sono già presenti; 7 operazioni su titoli senza movimento di
   * denaro non vengono registrate."
   */
  private tradeRepublicSummary(p: ImportPreview, from: string): string {
    const n = countOf;
    const parts: string[] = [];
    if (p.completeRows > 0) {
      parts.push(
        n(
          p.completeRows,
          '1 movimento ricevuto dalla banca verrà completato',
          'movimenti ricevuti dalla banca verranno completati',
        ) + ' con causale e controparte',
      );
    }
    parts.push(
      p.newRows === 0
        ? `nessun movimento precedente al ${from} da caricare`
        : n(
            p.newRows,
            `1 movimento precedente al ${from} verrà caricato`,
            `movimenti precedenti al ${from} verranno caricati`,
          ),
    );
    if (p.duplicateRows > 0) {
      parts.push(n(p.duplicateRows, '1 riga è già presente', 'righe sono già presenti'));
    }
    if (p.coveredRows > 0) {
      parts.push(
        n(
          p.coveredRows,
          '1 riga non trova un movimento della banca (vedi «Da verificare»)',
          'righe non trovano un movimento della banca (vedi «Da verificare»)',
        ),
      );
    }
    if (p.skippedRows > 0) {
      parts.push(
        n(
          p.skippedRows,
          '1 operazione su titoli senza movimento di denaro non viene registrata',
          'operazioni su titoli senza movimento di denaro non vengono registrate',
        ),
      );
    }
    let text = `Il file contiene ${n(p.totalRows, '1 riga', 'righe')}: ${parts.join('; ')}.`;
    if (p.newRows === 0 && p.completeRows === 0) {
      text += " Il contenuto del file è già tutto presente nell'app.";
    }
    return text;
  }

  /** "Aggiungi 43 movimenti" (conto collegato) o "Importa 75 righe" (conto manuale). */
  protected importLabel(p: ImportPreview): string {
    if (p.linkedAccount && p.completeRows > 0) {
      const complete = plural(p.completeRows, {
        one: '1 movimento',
        other: `${p.completeRows} movimenti`,
      });
      return p.newRows === 0
        ? `Completa ${complete}`
        : `Aggiungi ${p.newRows} e completa ${complete}`;
    }
    if (p.linkedAccount) {
      if (p.newRows === 0) {
        return 'Niente da aggiungere';
      }
      return plural(p.newRows, {
        one: 'Aggiungi 1 movimento',
        other: `Aggiungi ${p.newRows} movimenti`,
      });
    }
    const rows = p.totalRows - p.skippedRows; // le operazioni sui titoli di Trade Republic non si importano
    return plural(rows, { one: 'Importa 1 riga', other: `Importa ${rows} righe` });
  }

  private requestPreview(mapping: ImportMapping | null): void {
    if (this.file() && this.account.value !== null) {
      this.previewRequests.next(mapping);
    }
  }
}
