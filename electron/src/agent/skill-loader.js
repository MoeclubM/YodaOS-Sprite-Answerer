'use strict';
const SKILLS = {
  'calculus-algebra': { prompt: 'Solve with explicit algebraic steps, definitions, and a quick check.', keywords: ['calculus', 'derivative', 'integral', 'integrate', 'equation', 'algebra', 'quadratic', 'factor'], tools: ['calculate', 'integrate', 'search_knowledge'] },
  physics: { prompt: 'Use the relevant physical law, show units, assumptions, and substitutions.', keywords: ['physics', 'force', 'mass', 'momentum', 'velocity', 'newton', 'energy'], tools: ['calculate', 'search_knowledge'] },
  chemistry: { prompt: 'Balance the reaction and show molar relationships and units.', keywords: ['chemistry', 'chemical', 'mole', 'molar', 'reaction', 'atom', 'stoichiometry'], tools: ['calculate', 'search_knowledge'] },
  general: { prompt: 'Answer clearly and concisely; state assumptions and show verifiable steps.', keywords: ['what', 'how', 'why', 'explain', 'help'], tools: ['search_knowledge'] }
};
class SkillLoader {
  matchSkill(question) { const text = String(question || '').toLowerCase(); let best = 'general'; let score = 0; for (const [name, skill] of Object.entries(SKILLS)) { if (name === 'general') continue; const current = skill.keywords.reduce((n, word) => n + (text.includes(word) ? (text === word ? 4 : 1) : 0), 0); if (current > score) { score = current; best = name; } } return best; }
  getPrompt(skill) { return (SKILLS[typeof skill === 'string' ? skill : 'general'] || SKILLS.general).prompt; }
  getTools(skill) { return [...((SKILLS[skill] || SKILLS.general).tools)]; }
  getSkill(skill) { return SKILLS[skill] || SKILLS.general; }
}
const instance = new SkillLoader();
module.exports = { SKILLS, SkillLoader, skillLoader: instance, matchSkill: instance.matchSkill.bind(instance), getPrompt: instance.getPrompt.bind(instance), getTools: instance.getTools.bind(instance) };
