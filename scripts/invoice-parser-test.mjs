import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const html = readFileSync(new URL('../www/index.html', import.meta.url), 'utf8');
const start = html.indexOf('function normalizeOcrDate(');
const end = html.indexOf('async function showInvoiceText(', start);
assert(start >= 0 && end > start);
const recognize = new Function('text', 'direction', html.slice(start, end) + '\nreturn detectFourFields(text);');

const sample = `Elektronikus számla
2026-1234
ELADÓ                                                         VEVŐ
Minta Kft.                                                    Teszt Ügyfél
SZÁMLA KELTE : 2026. 09. 22.                                  FIZETÉSI HATÁRIDŐ: 2026. 09. 30.
FIZETENDŐ BRUTTÓ VÉGÖSSZEG:                                   14 149 Ft
NETTÓ ÖSSZEG:                                                 11 141 Ft
FIZETENDŐ BRUTTÓ VÉGÖSSZEG:                                   14 149 Ft`;
assert.deepEqual(recognize(sample, 'incoming'), {
  partner: 'Minta Kft.', number: '2026-1234', due: '2026-09-30', amount: '14149'
});
assert.equal(recognize(sample, 'outgoing').partner, 'Teszt Ügyfél');
assert.equal(recognize('Fizetési határidő: 2026. 09. 30.\nFizetési határidő: 2026. 10. 02.', 'incoming').due, '');
const bilingual = `Számla/Invoice
1234567890
Összesítő száma/List number: 2026/4000254090/DS01
Fizetési határidő/Payment due: 2026.09.17
Szállító/Seller:                         Vevő/Customer:
Másik Minta Zrt.                        Teszt Ügyfél
Fizetendő összesen/Total Amount:        80 547,00 HUF`;
assert.deepEqual(recognize(bilingual, 'incoming'), {
  partner: 'Másik Minta Zrt.', number: '1234567890', due: '2026-09-17', amount: '80547.00'
});
assert.equal(recognize(bilingual, 'outgoing').partner, 'Teszt Ügyfél');
assert.equal(recognize('ELADÓ VEVŐ\nMinta Kft. Teszt Ügyfél', 'incoming').partner, 'Minta Kft.');
console.log('invoice parser: PASS');
