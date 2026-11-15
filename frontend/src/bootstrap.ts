import { bootstrapApplication } from '@angular/platform-browser';

import { App } from './app/app';
import { appConfig } from './app/app.config';

/** Avvia l'app. Sta in un file suo perché main.ts lo carica solo dopo aver preparato la lingua. */
export function bootstrap(): Promise<unknown> {
  return bootstrapApplication(App, appConfig);
}
