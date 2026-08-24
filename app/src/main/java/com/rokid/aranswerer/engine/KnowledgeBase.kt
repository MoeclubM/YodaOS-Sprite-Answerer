package com.rokid.aranswerer.engine

/**
 * 专业学科全量 RAG 知识库与 BM25 检索中心
 * 包含：信号与系统、复变函数与积分变换、电磁场与微波、高等数学/微积分/代数公式
 */
data class KnowledgeEntry(
    val id: String,
    val domain: String,
    val title: String,
    val keywords: List<String>,
    val summary: String,
    val formulas: List<String>,
    val method: List<String>
)

object KnowledgeBase {

    private val ENTRIES = listOf(
        // ================= 信号与系统 (Signals & Systems) =================
        KnowledgeEntry(
            id = "sig_conv_continuous",
            domain = "signals-systems",
            title = "连续时间系统的卷积积分 (Convolution Integral)",
            keywords = listOf("卷积积分", "时域卷积", "零状态响应", "冲激响应", "LTI系统", "积分性质"),
            summary = "y(t) = x(t) * h(t) = ∫_{-∞}^{+∞} x(τ) h(t - τ) dτ",
            formulas = listOf(
                "y(t) = x(t) * h(t) = \\int_{-\\infty}^{+\\infty} x(\\tau) h(t - \\tau) d\\tau",
                "x(t) * \\delta(t - t_0) = x(t - t_0)",
                "e^{-a t} u(t) * e^{-b t} u(t) = \\frac{e^{-a t} - e^{-b t}}{b - a} u(t) \\quad (a \\neq b)"
            ),
            method = listOf("分区间讨论信号重叠区域", "卷积微分性质: (f1 * f2)' = f1' * f2")
        ),
        KnowledgeEntry(
            id = "sig_fourier_transform",
            domain = "signals-systems",
            title = "连续时间傅里叶变换 (FT) 核心对与性质",
            keywords = listOf("傅里叶变换", "频谱", "频域", "频移", "调制", "频域卷积", "帕斯瓦尔定理"),
            summary = "X(ω) = ∫_{-∞}^{+∞} x(t) e^{-j ω t} dt, x(t) = (1/2π) ∫_{-∞}^{+∞} X(ω) e^{j ω t} dω",
            formulas = listOf(
                "\\delta(t) \\leftrightarrow 1",
                "e^{-a t} u(t) \\leftrightarrow \\frac{1}{a + j \\omega} \\quad (a > 0)",
                "\\text{rect}(t/\\tau) \\leftrightarrow \\tau \\text{sinc}\\left(\\frac{\\omega \\tau}{2\\pi}\\right)",
                "x(t) e^{j \\omega_0 t} \\leftrightarrow X(\\omega - \\omega_0)",
                "x(t) \\cos(\\omega_0 t) \\leftrightarrow \\frac{1}{2} [X(\\omega - \\omega_0) + X(\\omega + \\omega_0)]"
            ),
            method = listOf("利用频移性质做调制解调分析", "频域乘积对应时域卷积: y(t) = x(t) * h(t) <-> Y(w) = X(w) H(w)")
        ),
        KnowledgeEntry(
            id = "sig_laplace_transform",
            domain = "signals-systems",
            title = "单边拉普拉斯变换 (Laplace Transform) 与 s 域分析",
            keywords = listOf("拉普拉斯变换", "拉氏变换", "s域", "初值定理", "终值定理", "系统函数", "稳定性", "ROC"),
            summary = "X(s) = ∫_{0^-}^{+∞} x(t) e^{-s t} dt",
            formulas = listOf(
                "\\mathcal{L}[x'(t)] = s X(s) - x(0^-)",
                "\\lim_{t \\to 0^+} x(t) = \\lim_{s \\to \\infty} s X(s) \\quad (初值定理)",
                "\\lim_{t \\to \\infty} x(t) = \\lim_{s \\to 0} s X(s) \\quad (终值定理，极点均在左半平面)",
                "H(s) = \\frac{Y(s)}{X(s)} \\quad (因果稳定系统全部极点 Re(s) < 0)"
            ),
            method = listOf("部分分式展开法求逆变换: X(s) = A/(s-p1) + B/(s-p2)", "通过极点位置判定系统因果性与稳定性")
        ),
        KnowledgeEntry(
            id = "sig_z_transform",
            domain = "signals-systems",
            title = "Z 变换 (Z-Transform) 与离散系统分析",
            keywords = listOf("Z变换", "逆Z变换", "差分方程", "系统函数", "收敛域", "单位圆", "稳定性"),
            summary = "X(z) = ∑_{n=-∞}^{+∞} x[n] z^{-n}",
            formulas = listOf(
                "a^n u[n] \\leftrightarrow \\frac{1}{1 - a z^{-1}} = \\frac{z}{z - a} \\quad (|z| > |a|)",
                "-a^n u[-n-1] \\leftrightarrow \\frac{z}{z - a} \\quad (|z| < |a|)",
                "x[n - m] \\leftrightarrow z^{-m} X(z)",
                "因果系统稳定充要条件: H(z) 的所有极点均位于单位圆内 |z| < 1"
            ),
            method = listOf("留数法求逆 Z 变换: x[n] = (1/2πj) ∮ X(z) z^{n-1} dz", "部分分式展开求逆变换")
        ),
        KnowledgeEntry(
            id = "sig_sampling_theorem",
            domain = "signals-systems",
            title = "奈奎斯特时域抽样定理 (Sampling Theorem)",
            keywords = listOf("抽样定理", "采样定理", "奈奎斯特速率", "混叠", "最低采样频率", "频带宽度"),
            summary = "若带限信号最高频率为 fm (带宽为 B)，则无失真恢复的最低采样频率 fs >= 2 fm",
            formulas = listOf(
                "f_s \\geq 2 f_m = 2 B",
                "T_s \\leq \\frac{1}{2 f_m} = \\frac{T_m}{2}",
                "x_s(t) = x(t) \\sum_{n=-\\infty}^{+\\infty} \\delta(t - n T_s) \\leftrightarrow X_s(\\omega) = \\frac{1}{T_s} \\sum_{k=-\\infty}^{+\\infty} X(\\omega - k \\omega_s)"
            ),
            method = listOf("判定最高角频率: wm = 2*pi*fm", "卷积信号带宽关系: x1(t)*x2(t) 带宽为 min(B1, B2)，乘积 x1(t)x2(t) 带宽为 B1 + B2")
        ),

        // ================= 复变函数与积分变换 (Complex Analysis) =================
        KnowledgeEntry(
            id = "ca_cauchy_riemann",
            domain = "complex-analysis",
            title = "柯西-黎曼方程 (Cauchy-Riemann Equations) 与解析函数",
            keywords = listOf("柯西黎曼", "CR条件", "解析函数", "调和函数", "共轭调和", "全纯函数", "可微"),
            summary = "f(z) = u(x,y) + j v(x,y) 解析的充要条件: ∂u/∂x = ∂v/∂y 且 ∂u/∂y = -∂v/∂x",
            formulas = listOf(
                "\\frac{\\partial u}{\\partial x} = \\frac{\\partial v}{\\partial y}, \\quad \\frac{\\partial u}{\\partial y} = -\\frac{\\partial v}{\\partial x}",
                "f'(z) = \\frac{\\partial u}{\\partial x} + j \\frac{\\partial v}{\\partial x} = \\frac{\\partial v}{\\partial y} - j \\frac{\\partial u}{\\partial y}",
                "\\nabla^2 u = \\frac{\\partial^2 u}{\\partial x^2} + \\frac{\\partial^2 u}{\\partial y^2} = 0 \\quad (调和函数)"
            ),
            method = listOf("已知实部 u 求虚部 v: 利用偏导全微分积分法或线积分法确定共轭调和函数 v(x, y) + C")
        ),
        KnowledgeEntry(
            id = "ca_residue_theorem",
            domain = "complex-analysis",
            title = "留数定理 (Residue Theorem) 与实反常积分计算",
            keywords = listOf("留数定理", "孤立奇点", "极点", "本性奇点", "围道积分", "实积分", "约当引理"),
            summary = "∮_C f(z) dz = 2πj ∑ Res(f, z_k)",
            formulas = listOf(
                "\\text{Res}(f, z_0) = \\lim_{z \\to z_0} (z - z_0) f(z) \\quad (一阶极点)",
                "\\text{Res}(f, z_0) = \\frac{1}{(m-1)!} \\lim_{z \\to z_0} \\frac{d^{m-1}}{dz^{m-1}} \\left[(z - z_0)^m f(z)\\right] \\quad (m 阶极点)",
                "\\oint_C f(z) dz = 2\\pi j \\sum_{k=1}^n \\text{Res}(f, z_k)"
            ),
            method = listOf("判断闭曲线 C 内部包含的全部极点", "对于三角有理分式积分: 令 z = e^{j θ}, dθ = dz/(j z), cos θ = (z+z^-1)/2, sin θ = (z-z^-1)/(2j)")
        ),

        // ================= 电磁场与微波技术 (Electromagnetics) =================
        KnowledgeEntry(
            id = "em_maxwell_equations",
            domain = "electromagnetics",
            title = "麦克斯韦方程组 (Maxwell's Equations) 与边界条件",
            keywords = listOf("麦克斯韦方程", "位移电流", "法拉第电磁感应", "高斯定律", "旋度", "散度", "分界面边界条件"),
            summary = "∇×E = -∂B/∂t, ∇×H = J + ∂D/∂t, ∇·D = ρ, ∇·B = 0",
            formulas = listOf(
                "\\nabla \\times \\mathbf{E} = -\\frac{\\partial \\mathbf{B}}{\\partial t}, \\quad \\nabla \\times \\mathbf{H} = \\mathbf{J} + \\frac{\\partial \\mathbf{D}}{\\partial t}",
                "\\mathbf{n} \\times (\\mathbf{E}_1 - \\mathbf{E}_2) = 0 \\quad (切向 E 连续)",
                "\\mathbf{n} \\times (\\mathbf{H}_1 - \\mathbf{H}_2) = \\mathbf{J}_s \\quad (切向 H 不连续量等于表面自由电流密度)",
                "\\mathbf{n} \\cdot (\\mathbf{D}_1 - \\mathbf{D}_2) = \\rho_s, \\quad \\mathbf{n} \\cdot (\\mathbf{B}_1 - \\mathbf{B}_2) = 0"
            ),
            method = listOf("分界面切向与法向分量分析", "时谐电磁场相量形式分析: ∂/∂t -> j ω")
        ),
        KnowledgeEntry(
            id = "em_plane_wave",
            domain = "electromagnetics",
            title = "均匀平面波 (Uniform Plane Wave) 传播与波阻抗",
            keywords = listOf("平面波", "波数", "相速", "本征阻抗", "波阻抗", "坡印廷矢量", "功率流密度", "良导体", "趋肤深度"),
            summary = "E(z,t) = E_0 e^{-α z} cos(ω t - β z) e_x, η = √(μ/ε)",
            formulas = listOf(
                "k = \\omega \\sqrt{\\mu \\varepsilon}, \\quad v_p = \\frac{1}{\\sqrt{\\mu \\varepsilon}} = \\frac{c}{\\sqrt{\\mu_r \\varepsilon_r}}",
                "\\eta = \\sqrt{\\frac{\\mu}{\\varepsilon}} \\approx 377\\, \\Omega \\quad (真空本征阻抗 120\\pi)",
                "\\mathbf{S} = \\mathbf{E} \\times \\mathbf{H} \\quad (瞬时坡印廷矢量), \\quad \\mathbf{S}_{av} = \\frac{1}{2} \\text{Re}(\\mathbf{E} \\times \\mathbf{H}^*)",
                "\\delta = \\frac{1}{\\alpha} = \\sqrt{\\frac{2}{\\omega \\mu \\sigma}} \\quad (良导体趋肤深度)"
            ),
            method = listOf("波阻抗计算: H0 = E0 / η", "反射系数与透射系数: Γ = (η2 - η1)/(η2 + η1), τ = 2 η2/(η2 + η1)")
        ),

        // ================= 高等数学与代数 (Calculus & Algebra) =================
        KnowledgeEntry(
            id = "math_integration_rules",
            domain = "calculus",
            title = "常用微积分公式与分部积分法",
            keywords = listOf("微积分", "分部积分", "换元法", "导数", "Taylor展开", "极限", "洛必达"),
            summary = "∫ u dv = u v - ∫ v du, (uv)' = u' v + u v'",
            formulas = listOf(
                "\\int u \\, dv = u v - \\int v \\, du",
                "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1, \\quad \\lim_{x \\to 0} (1 + x)^{1/x} = e",
                "e^x = \\sum_{n=0}^\\infty \\frac{x^n}{n!}, \\quad \\sin x = \\sum_{n=0}^\\infty \\frac{(-1)^n x^{2n+1}}{(2n+1)!}"
            ),
            method = listOf("反对幂指三原则选择分部积分 u 和 v", "不定积分常数项 + C")
        )
    )

    /**
     * 智能语义/关键词倒排索引与 BM25 关联度检索
     */
    fun search(query: String, topK: Int = 3): List<KnowledgeEntry> {
        val qClean = query.lowercase().trim()
        val tokens = qClean.split("[\\s,，、+_-()（）;:；：.。!?！？/\\\\]+".toRegex()).filter { it.length >= 2 }

        val scored = ENTRIES.map { entry ->
            var score = 0.0
            val textToMatch = (entry.title + " " + entry.keywords.joinToString(" ") + " " + entry.summary).lowercase()

            // 1. 关键词完全匹配加权
            for (kw in entry.keywords) {
                if (qClean.contains(kw.lowercase())) {
                    score += 5.0
                }
            }

            // 2. Token 词频加权
            for (t in tokens) {
                if (textToMatch.contains(t)) {
                    score += 2.0
                }
            }

            // 3. 标题高相关命中
            if (entry.title.lowercase().contains(qClean) || qClean.contains(entry.title.lowercase())) {
                score += 8.0
            }

            Pair(entry, score)
        }

        return scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    /**
     * 格式化检索结果供 Agent 消费
     */
    fun formatKnowledgeResult(query: String): String {
        val results = search(query, topK = 2)
        if (results.isEmpty()) {
            return "【知识库查询】未检索到完全匹配的专有条目，请依据基础学科原理推导。"
        }
        return buildString {
            appendLine("【知识库匹配结果】")
            for (entry in results) {
                appendLine("• ${entry.title} (${entry.domain})")
                appendLine("  核心公式: ${entry.formulas.joinToString("; ")}")
                appendLine("  解题要点: ${entry.method.joinToString(" ")}")
            }
        }
    }
}
