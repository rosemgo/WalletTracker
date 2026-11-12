import { Account } from '../api/models';

/**
 * Il conto è di Trade Republic? Il nome della banca viene da Enable Banking e può essere scritto in modi diversi
 * ("Trade Republic", "Trade Republic Bank GmbH", "TradeRepublic"): confrontiamo solo le lettere. Serve alla pagina
 * Importa (che a un conto Trade Republic collegato spiega il completamento con il suo file) e alla pagina Conti.
 */
export function isTradeRepublic(account: Account): boolean {
  return account.bank
    .toLowerCase()
    .replace(/[^a-z]/g, '')
    .includes('traderepublic');
}
