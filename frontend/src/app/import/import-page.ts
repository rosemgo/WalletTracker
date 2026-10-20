import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
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
import { WalletApi } from '../api/wallet-api';

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

/**
 * L'importazione dei movimenti da un file CSV, per i conti manuali (es. Trade Republic).
 *
 * Il percorso:
 * 1. scegli il conto e il file: il backend legge il file e PROPONE come interpretare le colonne;
 * 2. controlli l'anteprima e, se serve, correggi la mappatura: ogni modifica chiede una nuova anteprima;
 * 3. quando tutte le righe si leggono, importi.
 *
 * Il file resta nel browser: lo mandiamo al backend a ogni anteprima e all'importazione, che non lo
 * conserva.
 */
@Component({
  selector: 'app-import-page',
  imports: [CurrencyPipe, DatePipe, ReactiveFormsModule, RouterLink],
  templateUrl: './import-page.html',
})
export class ImportPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly route = inject(ActivatedRoute);

  protected readonly delimiters = DELIMITERS;
  protected readonly dateFormats = DATE_FORMATS;

  protected readonly accounts = signal<Account[]>([]); // solo i conti manuali
  protected readonly loading = signal(true);
  protected readonly file = signal<File | null>(null);
  protected readonly preview = signal<ImportPreview | null>(null);
  protected readonly result = signal<ImportResult | null>(null);
  protected readonly importing = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Le colonne del file, per i menu della mappatura: "1 · Data", "2 · Descrizione"... */
  protected readonly columns = computed(() =>
    (this.preview()?.headers ?? []).map((header, index) => ({
      index,
      label: `${index + 1} · ${header.trim() === '' ? '(senza nome)' : header}`,
    })),
  );

  /** Si può importare solo se ogni riga si legge con la mappatura scelta. */
  protected readonly canImport = computed(() => {
    const preview = this.preview();
    return (
      preview !== null &&
      preview.errorCount === 0 &&
      preview.parsedRows.length > 0 &&
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
    // Cambiando conto cambia la valuta predefinita: rifacciamo l'anteprima con la stessa mappatura.
    this.account.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.requestPreview(this.preview() ? this.mapping.getRawValue() : null));
  }

  ngOnInit(): void {
    // ?conto=5 (il link "importa un file" della pagina Conti) sceglie già il conto.
    const fromLink = Number(this.route.snapshot.queryParamMap.get('conto'));
    this.api.accounts().subscribe({
      next: (accounts) => {
        const manual = accounts.filter((a) => a.source === 'MANUAL');
        this.accounts.set(manual);
        const chosen = manual.find((a) => a.id === fromLink) ?? manual[0];
        this.account.setValue(chosen?.id ?? null, { emitEvent: false });
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

  /** "Importa un altro file": si ricomincia, con lo stesso conto. */
  restart(): void {
    this.result.set(null);
    this.preview.set(null);
    this.file.set(null);
    this.error.set(null);
  }

  /** Il nome del conto, nel messaggio finale. */
  protected accountName(): string {
    const account = this.accounts().find((a) => a.id === this.account.value);
    return account?.label ?? '';
  }

  private requestPreview(mapping: ImportMapping | null): void {
    if (this.file() && this.account.value !== null) {
      this.previewRequests.next(mapping);
    }
  }
}
