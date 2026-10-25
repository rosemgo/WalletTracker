import { CurrencyPipe, DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';

import { categoryGroups, kindOf } from '../api/category-groups';
import {
  AMOUNT_SIGNS,
  AMOUNT_SIGN_LABELS,
  MATCH_MODES,
  MATCH_MODE_LABELS,
  TYPES,
  TYPE_LABELS,
} from '../api/labels';
import {
  AmountSign,
  Category,
  Rule,
  RulePreview,
  RuleRequest,
  TransactionType,
} from '../api/models';
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
  categoryId: null,
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
  imports: [ReactiveFormsModule, CurrencyPipe, DatePipe, NgTemplateOutlet],
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
  /** Le regole create dall'utente, e quelle che vengono con l'app (in una sezione da aprire). */
  protected readonly ownRules = computed(() => this.rules().filter((r) => !r.builtin));
  protected readonly builtinRules = computed(() => this.rules().filter((r) => r.builtin));

  /**
   * La variabile di un ng-template (let-list) per TypeScript non ha un tipo: passando di qui torna un elenco di regole,
   * e il template controlla di nuovo i nomi dei campi.
   */
  protected asRules(list: Rule[]): Rule[] {
    return list;
  }
  protected readonly editing = signal<Rule | null>(null); // la regola in modifica; null = regola nuova
  protected readonly preview = signal<RulePreview | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  /** L'elenco delle categorie, per il menu "Categoria". */
  protected readonly categories = signal<Category[]>([]);

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
    categoryId: [NEW_RULE.categoryId as number | null], // l'id della categoria, oppure null
    priority: [NEW_RULE.priority, [Validators.required, Validators.min(0), Validators.max(10000)]],
    enabled: [NEW_RULE.enabled],
  });

  /**
   * Il tipo scelto nel modulo, come signal. `valueChanges` è un Observable che emette a ogni modifica del
   * campo; `toSignal` lo trasforma in un signal, così un `computed` può dipenderne.
   */
  private readonly resultType = toSignal(this.form.controls.resultType.valueChanges, {
    initialValue: this.form.controls.resultType.value,
  });

  /** Il menu delle categorie, con in cima il gruppo che si addice al tipo scelto. */
  protected readonly categoryGroups = computed(() =>
    categoryGroups(this.categories(), kindOf(this.resultType())),
  );

  ngOnInit(): void {
    this.loadRules();
    this.api.categories().subscribe({ next: (categories) => this.categories.set(categories) });
    this.prefillFromAddress();
  }

  /**
   * "Crea una regola da questa correzione" (pagina Movimenti) arriva qui con i dati nell'indirizzo:
   * /regole?pattern=NETFLIX&resultType=EXPENSE&amountSign=NEGATIVE&categoryId=8.
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
    // L'id deve essere un numero intero positivo; se non c'è nell'elenco, il backend risponde 400.
    const categoryId = Number(params.get('categoryId'));
    this.form.patchValue({
      name: `Movimenti con "${pattern}"`.slice(0, 100),
      pattern: pattern.slice(0, 200),
      ...(this.types.includes(resultType) ? { resultType } : {}),
      ...(this.amountSigns.includes(amountSign) ? { amountSign } : {}),
      ...(Number.isInteger(categoryId) && categoryId > 0 ? { categoryId } : {}),
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
    // setValue vuole TUTTI i campi del modulo.
    this.form.setValue({
      name: rule.name,
      pattern: rule.pattern,
      matchMode: rule.matchMode,
      amountSign: rule.amountSign,
      resultType: rule.resultType,
      categoryId: rule.categoryId,
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
    // Tutti i campi tranne quelli calcolati dal backend (id, nome della categoria, builtin, uses).
    const { id, category, builtin, uses, ...request } = rule;
    this.api.updateRule(id, { ...request, enabled: !rule.enabled }).subscribe({
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
