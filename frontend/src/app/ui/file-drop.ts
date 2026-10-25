import { Component, input, output, signal } from '@angular/core';

/**
 * La scelta di un file, al posto del pulsante "Sfoglia" del browser (docs/41, parte D): una zona tratteggiata con
 * un'icona, che si tocca per aprire la scelta del file o su cui si trascina il file dal computer.
 *
 * Il trucco: dentro c'è il vero <input type="file">, trasparente e grande quanto tutta la zona. Così un clic in
 * qualsiasi punto lo apre, il file trascinato ci cade sopra (il browser lo accetta da solo), e la tastiera e i lettori
 * di schermo lo trovano come prima. Noi disegniamo solo l'aspetto.
 *
 * Il file scelto arriva a chi usa il componente con l'evento originale (picked), come prima con (change).
 */
@Component({
  selector: 'app-file-drop',
  template: `
    <label class="file-drop" [class.dragging]="dragging()" [class.chosen]="fileName() !== null">
      <input
        type="file"
        [attr.accept]="accept()"
        (change)="onChange($event)"
        (dragenter)="dragging.set(true)"
        (dragleave)="dragging.set(false)"
        (drop)="dragging.set(false)"
      />
      <span class="file-drop-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          @if (fileName() === null) {
            <path d="M12 16V4m0 0-4.5 4.5M12 4l4.5 4.5M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" />
          } @else {
            <path
              d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zm0 0v5h5M9 14.5l2 2 4-4"
            />
          }
        </svg>
      </span>
      <span class="file-drop-text">
        <strong>{{ fileName() ?? title() }}</strong>
        <span class="secondary">{{
          fileName() === null ? hint() : 'Tocca per scegliere un altro file'
        }}</span>
      </span>
    </label>
  `,
})
export class FileDrop {
  /** I tipi di file accettati, come nell'attributo accept: ".csv,text/csv". */
  readonly accept = input('');
  /** La scritta principale: "Scegli il file CSV". */
  readonly title = input.required<string>();
  /** La scritta sotto: "o trascinalo qui · al massimo 5 MB". */
  readonly hint = input('o trascinalo qui');

  readonly picked = output<Event>();

  protected readonly fileName = signal<string | null>(null);
  protected readonly dragging = signal(false);

  protected onChange(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    this.fileName.set(file?.name ?? null);
    this.picked.emit(event);
  }
}
