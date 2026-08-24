# AR-Answerer — Electron 精简版

> 基于 `Rokid-Answerer` Agent 框架精简重构，解决 AIUI 技能/工具冗杂。  
> 单窗口 480×640 黑底绿线，内容靠上，下半留空，无操作指南。

## 1. 精简要点（对比 AIUI）

| AIUI 原版 | Electron 精简 |
|---|---|
| `skills 6md + tools 7js + BASE/SKILL_MAP/ALL` 三层 | 单 `tool-registry` + 单 `skill-loader` 懒加载，`vector_calculate` 等只注册一次 |
| 多技能并发注入，提示词 600tokens | `matchSkill` 取 `top1`，仅注入单技能 180tokens |
| `knowledge 3域` 分散聚合 | 单 `knowledge` 统一 BM25 索引 |
| `pipeline` 双实现（API/内置） | 单 `pipeline` + `provider` 抽象，主备回退 |
| 底部操作指南常驻 | 无指南，极简顶靠 |

## 2. 目录

```
AR-Answerer/
├─ electron/
│  ├─ main.js              # 480×640 黑绿窗，无菜单
│  ├─ preload.js           # expose solve/onProgress
│  ├─ renderer/
│  │  ├─ index.html        # 顶靠，无指南，占位已移除
│  │  ├─ style.css         # 黑 #000 绿 #40ff5e 线框，靠上
│  │  └─ renderer.js       # 选图/相机→DataURL→IPC solve
│  └─ src/agent/
│     ├─ config.js         # 单 Gemini 主力 + 2 后备
│     ├─ tool-registry.js  # 单表单例
│     ├─ skill-loader.js   # 单 Loader top1
│     ├─ knowledge.js      # 单索引
│     └─ pipeline.js       # 三阶段 Stage1→2→3
└─ docs/ELECTRON_REFACTOR.md
```

## 3. 模型（无切换 UI）

`electron/src/agent/config.js`

```js
PRIMARY_MODEL = 'gemini-3.7-flash' // 主力
FALLBACK_MODELS = ['gpt-5.6-luna', 'muse-spark-1.2'] // 自动回退，无 UI 切换
API_BASE = 'https://newapi.telecom.moe/v1'
```

`pipeline.js` 按序请求 `primary → fallback1 → fallback2`，前端无感知。

## 4. 快速开始

```bash
cd AR-Answerer/electron
npm i
# 配置 Key（或设环境变量 AR_API_KEY）
# set AR_API_KEY=sk-...  # Windows
# export AR_API_KEY=sk-...  # macOS/Linux
npm start
# 点 IMAGE 选图 或 CAMERA 预览（低亮度 0.38 轮廓），自动解题
```

## 5. UI 规范

- 黑底 ` #000` 绿 `#40ff5e` 线框 `1px`，内容靠上（`padding 16 16 0` `gap 12`），下半留空
- 顶部 `AR ANSWERER | status`，捕获区 `200px` 线框，答案区 `border-left 2px`，无底部操作指南

详见 `docs/ELECTRON_REFACTOR.md`
