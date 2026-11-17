import { HttpInterceptorFn } from '@angular/common/http';

import { APP_LANGUAGE } from '../text/locale';

/**
 * Dice al backend in che lingua rispondere: quella in cui questa pagina è aperta (header Accept-Language).
 *
 * Senza, il browser metterebbe la SUA lingua: con il browser in inglese e l'app in italiano, gli errori del backend
 * arriverebbero in inglese in mezzo a una pagina italiana. Solo per le nostre API.
 */
export const languageInterceptor: HttpInterceptorFn = (request, next) =>
  next(
    request.url.startsWith('/api/')
      ? request.clone({ setHeaders: { 'Accept-Language': APP_LANGUAGE } })
      : request,
  );
