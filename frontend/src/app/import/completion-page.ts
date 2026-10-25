import { CurrencyPipe, DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { Account, CompletionPreview, CompletionResult } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MAX_FILE_BYTES } from './import-page';

/**
 * Completa i movimenti di un conto collegato con il file della banca (docs/31). Trade Republic, con Enable
 * Banking, manda solo data e importo; il suo file "Esportazione transazioni" ha controparte e causale.
 *
 * Il percorso, come per l'importazione:
 * 1. scegli il conto e il file: il backend abbina ogni riga del file al suo movimento e mostra l'anteprima;
 * 2. se va bene, confermi: i movimenti ricevono i testi, quelli più vecchi dello storico della banca vengono
 *    aggiunti, e tutto viene classificato di nuovo.
 * Le righe recenti senza movimento non si aggiungono: le porta la sincronizzazione.
 */
@Component({
  selector: 'app-completion-page',
  imports: [CurrencyPipe, DatePipe, NgTemplateOutlet, ReactiveFormsModule, RouterLink],
  templateUrl: './completion-page.html',
})
export class CompletionPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly route = inject(ActivatedRoute);

  protected readonly accounts = signal<Account[]>([]); // solo i conti Trade Republic collegati
  protected readonly loading = signal(true);
  protected readonly file = signal<File | null>(null);
  protected readonly preview = signal<CompletionPreview | null>(null);
  protected readonly result = signal<CompletionResult | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly account = this.fb.control<number | null>(null);

  constructor() {
    this.account.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.requestPreview());
  }

  ngOnInit(): void {
    // Solo i conti Trade Republic collegati: è l'unica banca che manda i movimenti senza causale, e il file che la
    // pagina legge è il suo (ogni banca ne ha uno diverso). ?conto=5 (il link della pagina Conti) sceglie il conto.
    const fromLink = Number(this.route.snapshot.queryParamMap.get('conto'));
    this.api.accounts().subscribe({
      next: (accounts) => {
        const linked = accounts.filter((a) => a.source !== 'MANUAL' && isTradeRepublic(a));
        this.accounts.set(linked);
        const chosen = linked.find((a) => a.id === fromLink) ?? linked[0];
        this.account.setValue(chosen?.id ?? null, { emitEvent: false });
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossibile caricare i conti: il backend è avviato?');
        this.loading.set(false);
      },
    });
  }

  onFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    if (file && file.size > MAX_FILE_BYTES) {
      this.file.set(null);
      this.preview.set(null);
      this.error.set('Il file è troppo grande: al massimo 5 MB. Esporta un periodo più breve.');
      return;
    }
    this.file.set(file);
    this.requestPreview();
  }

  complete(): void {
    const file = this.file();
    const accountId = this.account.value;
    if (!file || accountId === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.completeTransactions(file, accountId).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.result.set(result);
      },
      error: (response) => {
        this.busy.set(false);
        this.error.set(errorOf(response, 'Il completamento non è riuscito.'));
      },
    });
  }

  /** "Il file va dal 01/07/2026 al 20/07/2026: 18 movimenti di denaro (e 4 operazioni sui titoli...)." */
  protected summary(p: CompletionPreview): string {
    const day = (iso: string) => iso.split('-').reverse().join('/');
    const skipped =
      p.skippedRows === 0
        ? ''
        : p.skippedRows === 1
          ? ' (e 1 operazione sui titoli, che non muove denaro e viene saltata)'
          : ` (e ${p.skippedRows} operazioni sui titoli, che non muovono denaro e vengono saltate)`;
    return `Il file va dal ${day(p.from)} al ${day(p.to)}: ${this.count(p.fileRows, 'movimento', 'movimenti')} di denaro${skipped}.`;
  }

  /** Il pulsante: "Completa 18 movimenti e aggiungine 63", "Completa 5 movimenti", "Aggiungi 3 movimenti". */
  protected action(p: CompletionPreview): string {
    const complete = p.toComplete.length;
    const add = p.toAdd.length;
    if (complete > 0 && add > 0) {
      return `Completa ${this.count(complete, 'movimento', 'movimenti')} e aggiungine ${add}`;
    }
    return complete > 0
      ? `Completa ${this.count(complete, 'movimento', 'movimenti')}`
      : `Aggiungi ${this.count(add, 'movimento', 'movimenti')}`;
  }

  protected resultMessage(done: CompletionResult): string {
    const parts = [
      this.count(done.completed, 'movimento completato', 'movimenti completati'),
      ...(done.added > 0 ? [this.count(done.added, 'aggiunto', 'aggiunti')] : []),
    ];
    const already =
      done.alreadyComplete > 0 ? ` (${done.alreadyComplete} righe erano già fatte)` : '';
    return `${parts.join(', ')}${already}.`;
  }

  /** "1 movimento", "5 movimenti". */
  protected count(n: number, one: string, many: string): string {
    return `${n} ${n === 1 ? one : many}`;
  }

  /** "Carica un altro file": si ricomincia, con lo stesso conto. */
  restart(): void {
    this.result.set(null);
    this.preview.set(null);
    this.file.set(null);
    this.error.set(null);
  }

  private requestPreview(): void {
    const file = this.file();
    const accountId = this.account.value;
    this.preview.set(null);
    this.error.set(null);
    if (!file || accountId === null) {
      return;
    }
    this.busy.set(true);
    this.api.previewCompletion(file, accountId).subscribe({
      next: (preview) => {
        this.busy.set(false);
        this.preview.set(preview);
      },
      error: (response) => {
        this.busy.set(false);
        this.error.set(errorOf(response, 'Il file non si può leggere.'));
      },
    });
  }
}

/**
 * Trade Republic è la banca per cui serve questa pagina (docs/31). Il nome viene da Enable Banking e può essere
 * scritto in modi diversi ("Trade Republic", "Trade Republic Bank GmbH"): confrontiamo solo le lettere. Lo stesso
 * controllo è nel backend (CompletionService.isTradeRepublic).
 */
export function isTradeRepublic(account: Account): boolean {
  return account.bank
    .toLowerCase()
    .replace(/[^a-z]/g, '')
    .includes('traderepublic');
}

/** Il messaggio del backend, con l'elenco delle righe sbagliate se c'è (campo "errors" del ProblemDetail). */
function errorOf(
  response: { error?: { detail?: string; errors?: string[] } },
  fallback: string,
): string {
  return [response.error?.detail ?? fallback, ...(response.error?.errors ?? [])].join(' ');
}
