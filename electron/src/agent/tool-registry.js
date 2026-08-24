'use strict';
const knowledge = require('./knowledge');

// A single registry prevents vector_calculate and domain variants being registered twice.
const TOOLS = {
  calculate: { def: { name: 'calculate', description: 'Evaluate a basic mathematical expression.', parameters: { type: 'object', properties: { expression: { type: 'string' } }, required: ['expression'] } }, fn: args => { const expression = String(args?.expression || args?.formula || ''); if (!/^[0-9+\-*/().,%^ xsqrtpi]+$/i.test(expression)) throw new Error('Unsupported expression'); const value = expression.replace(/\^/g, '**').replace(/sqrt/gi, 'Math.sqrt').replace(/\bpi\b/gi, 'Math.PI').replace(/x/gi, '*'); return Function(`"use strict"; return (${value})`)(); } },
  search_knowledge: { def: { name: 'search_knowledge', description: 'Search the compact subject knowledge index.', parameters: { type: 'object', properties: { query: { type: 'string' }, topK: { type: 'number' } }, required: ['query'] } }, fn: args => knowledge.search(args?.query, args?.topK || 3) },
  web_search: { def: { name: 'web_search', description: 'Fetch a web search endpoint when configured.', parameters: { type: 'object', properties: { query: { type: 'string' } }, required: ['query'] } }, fn: async args => { if (!process.env.AR_WEB_SEARCH_URL) return { query: args?.query || '', results: [], unavailable: true }; const url = `${process.env.AR_WEB_SEARCH_URL}?q=${encodeURIComponent(args?.query || '')}`; const response = await fetch(url); if (!response.ok) throw new Error(`Web search failed (${response.status})`); return response.json(); } }
};
const factories = {
  integrate: () => ({ def: { name: 'integrate', description: 'Numerically integrate f(x) on an interval.', parameters: { type: 'object', properties: { expression: { type: 'string' }, from: { type: 'number' }, to: { type: 'number' } }, required: ['expression', 'from', 'to'] } }, fn: ({ expression, from = 0, to = 1 }) => { const source = String(expression).replace(/\^/g, '**').replace(/\bx\b/g, 'x'); if (!/^[0-9x+\-*/(). *]+$/.test(source)) throw new Error('Unsupported integrand'); const f = Function('x', `"use strict"; return (${source})`); const n = 200; const h = (to - from) / n; let sum = f(from) + f(to); for (let i = 1; i < n; i++) sum += f(from + i * h) * (i % 2 ? 4 : 2); return sum * h / 3; } })
};
async function loadTool(name) { if (TOOLS[name]) return TOOLS[name]; const factory = factories[name]; if (!factory) throw new Error(`Unknown tool: ${name}`); TOOLS[name] = factory(); return TOOLS[name]; }
async function execute(name, args = {}) { const tool = await loadTool(name); return tool.fn(args); }
function getToolsForSkill(skill) { const names = Array.isArray(skill) ? skill : (skill?.tools || []); return [...new Set(names)].map(name => TOOLS[name]?.def || { name, description: 'On-demand tool' }); }
module.exports = { TOOLS, loadTool, execute, getToolsForSkill };
