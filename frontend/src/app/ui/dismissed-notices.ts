import { Injectable, signal } from '@angular/core';

/** Dove il browser ricorda gli avvisi chiusi (vale solo su questo dispositivo). */
export const DISMISSED_KEY = 'wallettracker.dismissed-notices';

/** Quanti avvisi chiusi ricordiamo al massimo: i più vecchi si dimenticano (e, se servono ancora, ricompaiono). */
export const MAX_REMEMBERED = 200;

/**
 * Gli avvisi chiusi dall'utente (docs/avvisi-chiudibili.md). Ogni avviso ha una CHIAVE che descrive il suo contenuto:
 * "only-transfers:Banca Alfa ··0001", "alert:BUDGET_OVER:2026-11:Spesa,Ristoranti". Chiuso un avviso, resta nascosto
 * finché la chiave è la stessa; se la situazione cambia (un altro conto, un altro mese, un altro errore) la chiave
 * cambia e l'avviso ricompare. Così chiudere un avviso non nasconde per sempre un problema nuovo.
 *
 * Due modi di chiudere:
 * - {@link dismiss}: ricordato nel browser (localStorage), anche dopo aver chiuso l'app;
 * - {@link dismissForNow}: solo fino alla prossima apertura dell'app (per gli stati passeggeri, come "senza rete").
 *
 * Se il browser non permette di ricordare (navigazione privata, dati bloccati), l'avviso resta chiuso fino alla
 * prossima apertura: non è un errore.
 */
@Injectable({ providedIn: 'root' })
export class DismissedNotices {
  /** Le chiavi chiuse, ricordate e non; un signal, così i template si aggiornano quando se ne chiude una. */
  private readonly keys = signal<ReadonlySet<string>>(new Set(this.saved()));

  isDismissed(key: string): boolean {
    return this.keys().has(key);
  }

  /** Chiude l'avviso e lo ricorda nel browser. */
  dismiss(key: string): void {
    this.add(key);
    try {
      const remembered = this.saved().filter((k) => k !== key);
      remembered.push(key);
      localStorage.setItem(DISMISSED_KEY, JSON.stringify(remembered.slice(-MAX_REMEMBERED)));
    } catch {
      // Non ricordato: resta chiuso fino alla prossima apertura dell'app.
    }
  }

  /** Chiude l'avviso solo fino alla prossima apertura dell'app. */
  dismissForNow(key: string): void {
    this.add(key);
  }

  private add(key: string): void {
    this.keys.update((keys) => new Set(keys).add(key));
  }

  private saved(): string[] {
    try {
      const value: unknown = JSON.parse(localStorage.getItem(DISMISSED_KEY) ?? '[]');
      return Array.isArray(value) ? value.filter((k): k is string => typeof k === 'string') : [];
    } catch {
      return [];
    }
  }
}
