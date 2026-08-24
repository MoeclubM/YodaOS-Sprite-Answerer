'use strict';
const { getConfig } = require('./config');
const { matchSkill, getPrompt, getTools } = require('./skill-loader');
const registry = require('./tool-registry');
const knowledge = require('./knowledge');

const notify = (onProgress, stage, message, extra = {}) => {
  if (typeof onProgress === 'function') onProgress({ stage, message, ...extra });
};

function extract(input) {
  if (typeof input === 'string') return input;
  if (!input || typeof input !== 'object') return '';
  return String(input.question || input.text || input.prompt || input.imageText || '');
}

async function solve(input = {}, options = {}) {
  const onProgress = input.onProgress || options.onProgress || input.progress || options.progress;
  const config = getConfig(input.config || options.config);
  const models = [config.PRIMARY_MODEL, ...(config.FALLBACK_MODELS || [])].filter(Boolean);
  try {
    notify(onProgress, 'stage1', 'Extracting');
    const question = extract(input);
    if (!question.trim() && !input.dataUrl) throw new Error('No question');
    const skill = matchSkill(question || 'general');
    const toolNames = getTools(skill);
    notify(onProgress, 'stage1', `Skill ${skill}`, { skill, tools: toolNames });
    const context = knowledge.search(question, 3);
    notify(onProgress, 'stage2', 'Tools', { skill });
    const toolResults = {};
    for (const name of toolNames) {
      if (name === 'search_knowledge') toolResults[name] = context;
      else if (name === 'calculate' && input.expression) toolResults[name] = await registry.execute(name, { expression: input.expression });
    }

    // 无 API_KEY 直接失败，不返回 mock
    if (!config.API_KEY) {
      throw new Error('Missing API_KEY');
    }
    // 主备轮询：Gemini 失败自动切 gpt-5.6-luna → muse-spark-1.2
    let lastErr = null;
    for (let i = 0; i < models.length; i++) {
      const model = models[i];
      try {
        notify(onProgress, 'stage2', `Model ${model}`, { model, attempt: i + 1 });
        const res = await fetch(`${config.API_BASE.replace(/\/$/, '')}/chat/completions`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.API_KEY}` },
          body: JSON.stringify({
            model,
            messages: [
              { role: 'system', content: getPrompt(skill) },
              { role: 'user', content: `${question}\nContext: ${JSON.stringify(context)}\nTools: ${JSON.stringify(toolResults)}` }
            ]
          })
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        const answer = data.choices?.[0]?.message?.content?.trim();
        if (!answer) throw new Error('Empty answer');
        notify(onProgress, 'stage3', 'Done', { model });
        return { answer, question, skill, context, toolResults, model };
      } catch (e) {
        lastErr = e;
        notify(onProgress, 'stage2', `Fallback ${i + 1} failed`, { error: e.message });
        if (i === models.length - 1) throw e;
      }
    }
    throw lastErr || new Error('All models failed');
  } catch (error) {
    notify(onProgress, 'error', error.message);
    return { answer: 'Unable to solve.', error: error.message };
  }
}

const run = (dataUrl, onProgress) => solve({ dataUrl, question: dataUrl ? 'image question' : '' }, { onProgress });
module.exports = { solve, run, process: solve, PipelineEngine: { solve } };

// 兼容 Electron 旧 runPipeline 导出
module.exports.runPipeline = async (dataUrl, onProgress) => {
  const r = await solve({ dataUrl, question: 'image' }, { onProgress });
  return r.answer;
};
