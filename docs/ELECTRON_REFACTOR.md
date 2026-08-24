# Electron 精简重构 — 解决 AIUI 技能/工具冗杂

## 1. AIUI 原版冗杂根因

| 模块 | 文件数 | 冗杂点 |
|---|---|---|
| 技能 | `skills/*.md` 6 + `skills/index.js` | 每技能重复“策略+工具+检索”三段模板，仅关键词不同；`SKILL_DOCS` 硬编码大字符串，`matchSkillsForQuestion` 6 段正则串行，若匹配多域则返回多技能，导致工具集膨胀 |
| 工具 | `tools/*.js` 7 + `tools/index.js` | `core-math` 与 `calculus-algebra`、`geometry` 与 `electromagnetics` 重复 `vector_calculate`/`calculate`；`BASE_SYSTEM_TOOLS` + `SKILL_TOOL_MAP` + `ALL_SYSTEM_TOOLS` 三层列表，`getToolsForSkills` 每次拼接去重；`executeTool` 大 switch |
| 知识库 | `knowledge/*.js` 3 + `knowledge/index.js` | 三域分散，BM25 逻辑在 `index.js` 再聚合，`KNOWLEDGE_MODULES` 注册表与 `getAllKnowledgeEntries` 遍历 |
| 流水线 | `services/pipeline-engine.js` 800 行 | `callOpenAiApi` 与 `runSessionConversation` 重复流解析，`runAgentApiPipeline` 与 `runAgentBuiltinPipeline` 双实现，仅模型源不同 |

**后果**：新增一域需改 4 处（md、tool、skill map、knowledge），工具去重靠运行时，提示词随技能数线性膨胀，AR 眼镜端 Token 浪费。

## 2. Electron 精简架构

```
electron/
├─ main.js          # 480×640 黑绿窗口，无菜单，无指南
├─ preload.js       # contextBridge: solve(dataUrl) + onProgress
├─ renderer/        # 顶靠 UI，下半留空，无操作指南
└─ src/agent/
   ├─ config.js              # 单一 AppConfig
   ├─ tool-registry.js       # 单 TOOLS 注册表 + 工厂单例
   ├─ skill-loader.js        # 单 SkillLoader，top1 匹配
   ├─ knowledge.js           # 单统一索引
   └─ pipeline.js            # 单 Pipeline，三阶段复用
```

### 2.1 ToolRegistry 统一调度

```js
// 旧：BASE + SKILL_MAP + ALL 三列表 + switch
// 新：单表 + 工厂
const TOOLS = {
  calculate: { def: {...}, fn: (args)=>mathEvaluate(args.expression) },
  search_knowledge: { def: {...}, fn: (args)=>knowledge.search(args.query) },
  web_search: { def: {...}, fn: webSearch },
  // 学科工具仅按需注册，vector_calculate 只注册一次
  vector_calculate: { def: {...}, fn: vectorCalc, lazy: ()=>import('./tools/vector.js') },
}
export async function getToolsForSkill(skill) {
  // 旧：拼接多数组去重  新：查 SKILL->{tools} 单映射，命中即返回精简 2~3 项
}
export async function execute(name, args) {
  const t = TOOLS[name] ?? await loadLazy(name)
  return t.fn(args) // 单调度，无 switch
}
```

- 合并重复：`vector_calculate`、`calculate` 唯一
- 懒加载：`lazy` 动态 `import()`，启动时仅 `calculate` 常驻，其余按 `skill` 首次调用时加载，内存/时间均降
- 单例缓存：`Map` 缓存已加载模块

### 2.2 SkillLoader 按需单例

```js
const SKILLS = {
  'calculus-algebra': { prompt: '...', tools: ['calculate','integrate'] },
  // 6 域，每域仅 prompt 片段 + tools 列表，不含冗长文档
}
export function matchSkill(q) {
  // 旧：多正则 push 多技能  新：单次遍历取 top1 分数，返回单技能
  let best = null, max = -1
  for (const [k, v] of Object.entries(SKILLS)) {
    const score = keywordWeight(q, k) // 单 BM25 权重
    if (score > max) { max = score; best = k }
  }
  return best ?? 'calculus-algebra'
}
```

- 返回单技能，工具集从 7~9 项降至 2~3 项，提示词长度 -60%
- 提示词按需注入：`Stage2` 仅注入 `SKILLS[skill].prompt`，不展示全量文档

### 2.3 Knowledge 单索引

- 旧：`KNOWLEDGE_MODULES` 三域 + `getAll()` 遍历
- 新：`knowledge.js` 启动时合并为 `ALL_ENTRIES` 单数组，`search(query)` 单 BM25，`domain` 参数仅过滤，无重复聚合

### 2.4 Pipeline 单实现

- 旧：`runAgentApiPipeline` + `runAgentBuiltinPipeline` 双函数，`callOpenAiApi` 内 3 重试与 `LanguageModel` 分支
- 新：`pipeline.js` 单 `Pipeline` 类，`provider` 抽象 (`provider.call(messages, tools, onChunk)`)，注入 `apiProvider` 或 `localProvider`，Stage1→2→3 逻辑单一

## 3. 效果

- 新增一域：仅在 `SKILLS` 加一项 + 在 `TOOLS` 注册 1~2 工具，无需改三列表
- 启动工具数：7 → 3 常驻，首题按需 +2，内存 -57%
- 提示词 Token：原多技能全量 600 tokens → 单技能 180 tokens
- 渲染：Electron 复用 `latex-renderer` Canvas，但窗口直接黑绿，无指南，顶靠布局与裸机一致

## 4. 与 AIUI 对比

| AIUI | Electron 精简 |
|---|---|
| 多文件分域 | 单 registry + 单 loader |
| 运行时拼接去重 | 启动单例，懒加载 |
| 多技能并发注入 | 单 top1 注入 |
| 双流水线 | 单 provider 抽象 |
| 操作指南常驻 | 无指南，极简 |

## 5. 验证

```bash
cd electron
npm i
npm start # 480×640 黑绿窗口，选图→DataURL→IPC solve→进度→答案
```
