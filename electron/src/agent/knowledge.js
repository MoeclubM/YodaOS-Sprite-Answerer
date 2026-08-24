'use strict';

// One compact index replaces the old per-domain knowledge files and repeated loaders.
const DOCUMENTS = [
  { id: 'calculus', domain: 'calculus-algebra', text: 'Derivative: the limit of the change quotient. Integral is an antiderivative; the fundamental theorem connects differentiation and integration.' },
  { id: 'algebra', domain: 'calculus-algebra', text: 'Solve linear equations by applying inverse operations to both sides. Factor quadratics before using the quadratic formula.' },
  { id: 'physics', domain: 'physics', text: 'Newton second law is F = m a. Momentum is p = m v and is conserved in an isolated system.' },
  { id: 'chemistry', domain: 'chemistry', text: 'Balance atoms in a chemical equation before interpreting stoichiometric coefficients. Moles use n = m / M.' },
  { id: 'general', domain: 'general', text: 'Show assumptions, units, and intermediate steps so an answer can be checked.' }
];
const tokenize = value => String(value || '').toLowerCase().match(/[\p{L}\p{N}]+/gu) || [];
const frequencies = DOCUMENTS.map(doc => { const map = new Map(); tokenize(doc.text).forEach(t => map.set(t, (map.get(t) || 0) + 1)); return map; });
const avgLength = DOCUMENTS.reduce((n, d) => n + tokenize(d.text).length, 0) / DOCUMENTS.length;
function search(query, topK = 3) {
  const terms = tokenize(query); const N = DOCUMENTS.length; const scores = DOCUMENTS.map((doc, i) => {
    const words = tokenize(doc.text); const dl = words.length; let score = 0;
    for (const term of terms) { const tf = frequencies[i].get(term) || 0; if (!tf) continue; const df = DOCUMENTS.reduce((n, _, j) => n + (frequencies[j].has(term) ? 1 : 0), 0); const idf = Math.log(1 + (N - df + 0.5) / (df + 0.5)); score += idf * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * dl / avgLength)); }
    return { ...doc, score };
  });
  return scores.filter(x => x.score > 0).sort((a, b) => b.score - a.score).slice(0, Math.max(1, topK));
}
module.exports = { DOCUMENTS, search };
