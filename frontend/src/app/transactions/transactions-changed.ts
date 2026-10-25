import { Injectable, signal } from '@angular/core';

/**
 * Un "segnale" condiviso tra il dettaglio di un movimento e l'elenco (docs/37, parte E): quando il dettaglio cambia
 * qualcosa (categoria, tipo, nota), l'elenco accanto si ricarica. Sul PC si vedono insieme; sul telefono l'elenco è
 * già aggiornato quando ci si torna.
 */
@Injectable({ providedIn: 'root' })
export class TransactionsChanged {
  /** Cresce di uno a ogni cambiamento: chi lo legge in un `effect` viene richiamato. */
  readonly version = signal(0);

  /** Una ricerca chiesta dal dettaglio ("Tutti i movimenti di Amazon"): l'elenco la scrive nel campo e cerca. */
  readonly search = signal<{ text: string } | null>(null);

  notify(): void {
    this.version.update((v) => v + 1);
  }

  /** Un oggetto nuovo a ogni richiesta: anche la stessa ricerca ripetuta fa ripartire l'elenco. */
  searchFor(text: string): void {
    this.search.set({ text });
  }
}
