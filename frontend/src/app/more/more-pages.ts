/** Una voce della navigazione. */
export interface NavItem {
  path: string;
  label: string;
  /** Il tracciato dell'icona (SVG 24×24, a linea); le pagine di servizio non ne hanno. */
  icon?: string;
  /** Una riga che dice a che cosa serve la pagina (nell'elenco "Altro"). */
  hint?: string;
}

/**
 * Le pagine "di servizio": si usano di rado, quasi sempre dal PC (docs/33, parte B). Sul telefono stanno sotto
 * "Altro"; sul PC sono nella colonna, sotto le sezioni principali.
 */
export const MORE_PAGES: NavItem[] = [
  {
    path: '/abbonamenti',
    label: $localize`Abbonamenti`,
    hint: $localize`le spese che tornano ogni mese o ogni anno`,
  },
  {
    path: '/investimenti',
    label: $localize`Investimenti`,
    hint: $localize`quanto hai versato, cedole, dividendi e interessi`,
  },
  {
    path: '/conti',
    label: $localize`Conti`,
    hint: $localize`i conti collegati e quelli aggiunti a mano`,
  },
  {
    path: '/collega',
    label: $localize`Collega una banca`,
    hint: $localize`un conto nuovo, o il rinnovo del consenso`,
  },
  {
    path: '/importa',
    label: $localize`Importa`,
    hint: $localize`i movimenti da un file CSV, e il file di Trade Republic`,
  },
  {
    path: '/regole',
    label: $localize`Regole`,
    hint: $localize`come i movimenti ricevono tipo e categoria`,
  },
  {
    path: '/categorie',
    label: $localize`Categorie`,
    hint: $localize`le tue categorie di spesa e di entrata`,
  },
  {
    path: '/impostazioni',
    label: $localize`Impostazioni`,
    hint: $localize`banca, intelligenza artificiale, tema`,
  },
];
