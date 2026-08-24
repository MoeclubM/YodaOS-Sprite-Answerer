'use strict';

/**
 * 精简模型配置 — 单 Gemini 主力 + 2 后备，无内置模型切换 UI
 * 解决 AIUI 多模型列表冗杂：原 API_MODELS 4+ 选项 + 内置模型分支已移除
 */
const API_BASE = process.env.AR_API_BASE || process.env.API_BASE || 'https://newapi.telecom.moe/v1';
const API_KEY = process.env.AR_API_KEY || process.env.API_KEY || '';

// 主力 + 后备，不做 UI 切换，按序回退
const PRIMARY_MODEL = process.env.AR_PRIMARY_MODEL || 'gemini-3.7-flash';
const FALLBACK_MODELS = [
  process.env.AR_FALLBACK_1 || 'gpt-5.6-luna',
  process.env.AR_FALLBACK_2 || 'muse-spark-1.2'
];

const MODELS = {
  primary: PRIMARY_MODEL,
  fallbacks: FALLBACK_MODELS,
  // 兼容旧字段
  answer: PRIMARY_MODEL,
  vision: PRIMARY_MODEL
};

function getConfig(overrides = {}) {
  return {
    API_BASE: overrides.API_BASE || API_BASE,
    API_KEY: overrides.API_KEY ?? API_KEY,
    MODELS: { ...MODELS, ...(overrides.MODELS || {}) },
    PRIMARY_MODEL: overrides.PRIMARY_MODEL || PRIMARY_MODEL,
    FALLBACK_MODELS: overrides.FALLBACK_MODELS || FALLBACK_MODELS
  };
}

module.exports = { API_BASE, API_KEY, MODELS, PRIMARY_MODEL, FALLBACK_MODELS, getConfig };
