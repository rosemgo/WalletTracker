import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';

import {
  AMOUNT_SIGNS,
  AMOUNT_SIGN_LABELS,
  MATCH_MODES,
  MATCH_MODE_LABELS,
  TYPES,
  TYPE_LABELS,
} from '../api/labels';
import { AmountSign, Rule, RulePreview, RuleRequest, TransactionType } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** I valori del modulo per una regola nuova. */
const NEW_RULE: RuleRequest = {
  name: '',
  priority: 100,
  enabled: true,
  matchMode: 'CONTAINS',
  pattern: '',
  amountSign: 'ANY',
  resultType: 'EXPENSE',
  category: '',
};

/**
 * La pagina delle regole: elenco, modulo per crearne o modificarne una, prova prima di salvare.
 *
 * Il modulo usa i **Reactive Forms** di Angular: il modulo è descritto nella classe (`form`), con i suoi
 * campi e le regole di validazione, e il template si limita a collegare ogni campo HTML al suo controllo
 * (`formControlName`). Così la classe conosce sempre il valore e la validità di ogni campo.
 */
@Component({
  selector: 'app-rules-page',
  imports: [ReactiveFormsModule, CurrencyPipe, DatePipe],
  templateUrl: './rules-page.html',
})
export class RulesPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly route = inject(ActivatedRoute);

  /**
   * Il "costruttore di moduli". NonNullable: con reset() i campi tornano al valore iniziale, non a null,
   * quindi i tipi restano semplici (string invece di string | null).
   */
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly types = TYPES;
  protected readonly typeLabels = TYPE_LABELS;
  protected readonly matchModes = MATCH_MODES;
  protected readonly matchModeLabels = MATCH_MODE_LABELS;
  protected readonly amountSigns = AMOUNT_SIGNS;
  protected readonly amountSignLabels = AMOUNT_SIGN_LABELS;

  protected readonly rules = signal<Rule[]>([]);
  protected readonly editing = signal<Rule | null>(null); // la regola in modifica; null = regola nuova
  protected readonly preview = signal<RulePreview | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Le categorie già usate, suggerite nel campo "Categoria". */
  protected readonly categories = signal<string[]>([]);

  /**
   * Il modulo: un FormGroup con un FormControl per ogni campo. Per ogni campo: [valore iniziale, validatori].
   * I validatori sono gli stessi controlli del backend (RuleRequest.java): li ripetiamo qui per avvisare
   * subito l'utente, ma l'ultima parola resta al backend.
   */
  protected readonly form = this.fb.group({
    name: [NEW_RULE.name, [Validators.required, Validators.maxLength(100)]],
    pattern: [
      NEW_RULE.pattern,
      [Validators.required, Validators.minLength(2), Validators.maxLength(200)],
    ],
    matchMode: [NEW_RULE.matchMode],
    amountSign: [NEW_RULE.amountSign],
    resultType: [NEW_RULE.resultType],
    category: [NEW_RULE.category, [Validators.maxLength(100)]],
    priority: [NEW_RULE.priority, [Validators.required, Validators.min(0), Validators.max(10000)]],
    enabled: [NEW_RULE.enabled],
  });

  ngOnInit(): void {
    this.loadRules();
    this.api.categories().subscribe({ next: (categories) => this.categories.set(categories) });
    this.prefillFromAddress();
  }

  /**
   * "Crea una regola da questa correzione" (pagina Movimenti) arriva qui con i dati nell'indirizzo:
   * /regole?pattern=NETFLIX&resultType=EXPENSE&amountSign=NEGATIVE&category=Abbonamenti.
   * Compiliamo il modulo e facciamo subito la prova, così l'utente vede a quali movimenti si
   * applicherebbe prima di salvare. I valori che non conosciamo vengono ignorati: l'indirizzo lo può
   * scrivere chiunque, quindi non ci fidiamo (e comunque l'ultima parola resta al backend).
   */
  private prefillFromAddress(): void {
    const params = this.route.snapshot.queryParamMap;
    const pattern = params.get('pattern')?.trim();
    if (!pattern) {
      return;
    }
    const resultType = params.get('resultType') as TransactionType;
    const amountSign = params.get('amountSign') as AmountSign;
    this.form.patchValue({
      name: `Movimenti con "${pattern}"`.slice(0, 100),
      pattern: pattern.slice(0, 200),
      ...(this.types.includes(resultType) ? { resultType } : {}),
      ...(this.amountSigns.includes(amountSign) ? { amountSign } : {}),
      category: (params.get('category') ?? '').slice(0, 100),
    });
    this.message.set(
      'Modulo compilato dalla tua correzione: accorcia il testo da cercare se serve, controlla la prova e salva.',
    );
    this.tryRule();
  }

  /** "Modifica": carica la regola nel modulo. */
  edit(rule: Rule): void {
    this.editing.set(rule);
    this.preview.set(null);
    this.message.set(null);
    // setValue vuole TUTTI i campi del modulo; la categoria null diventa una stringa vuota.
    this.form.setValue({
      name: rule.name,
      pattern: rule.pattern,
      matchMode: rule.matchMode,
      amountSign: rule.amountSign,
      resultType: rule.resultType,
      category: rule.category ?? '',
      priority: rule.priority,
      enabled: rule.enabled,
    });
  }

  /** Torna al modulo vuoto, per una regola nuova. */
  cancel(): void {
    this.editing.set(null);
    this.preview.set(null);
    this.form.reset(NEW_RULE);
  }

  /** "Prova": a quali movimenti si applicherebbe la regola, senza salvarla. */
  tryRule(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched(); // mostra gli errori di tutti i campi
      return;
    }
    this.api.previewRule(this.form.getRawValue()).subscribe({
      next: (preview) => this.preview.set(preview),
      error: (response) => this.error.set(response.error?.detail ?? 'Prova non riuscita.'),
    });
  }

  /** "Salva": crea una regola nuova o aggiorna quella in modifica. */
  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const request = this.form.getRawValue(); // i valori di tutti i campi, come oggetto RuleRequest
    const editing = this.editing();
    const saved$ = editing
      ? this.api.updateRule(editing.id, request)
      : this.api.createRule(request);

    this.error.set(null);
    saved$.subscribe({
      next: (rule) => {
        this.message.set(`Regola "${rule.name}" salvata: ora classifica ${rule.uses} movimenti.`);
        this.cancel();
        this.loadRules();
      },
      error: (response) =>
        this.error.set(response.error?.detail ?? 'La regola non è stata salvata.'),
    });
  }

  /** La casella "Attiva" di una riga: salva la regola con enabled cambiato. */
  toggle(rule: Rule): void {
    const { id, builtin, uses, ...request } = rule; // tutti i campi tranne id, builtin e uses
    this.api
      .updateRule(id, { ...request, category: rule.category ?? '', enabled: !rule.enabled })
      .subscribe({
        next: () => this.loadRules(),
        error: () => this.error.set('La regola non è stata aggiornata.'),
      });
  }

  remove(rule: Rule): void {
    this.api.deleteRule(rule.id).subscribe({
      next: () => {
        this.message.set(`Regola "${rule.name}" cancellata.`);
        this.loadRules();
      },
      error: (response) =>
        this.error.set(response.error?.detail ?? 'La regola non è stata cancellata.'),
    });
  }

  /** Il campo ha un errore da mostrare? Solo dopo che l'utente ci è passato (touched). */
  protected showError(field: 'name' | 'pattern' | 'priority'): boolean {
    const control = this.form.controls[field];
    return control.invalid && control.touched;
  }

  private loadRules(): void {
    this.api.rules().subscribe({
      next: (rules) => this.rules.set(rules),
      error: () => this.error.set('Impossibile caricare le regole: il backend è avviato?'),
    });
  }
}
