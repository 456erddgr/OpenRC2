package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * 主题与绘制工具（不依赖任何 UI 库）。
 *
 * 2026-11-07 重做视觉语言，参照 DJI 设计系统（awesome-design-md/design-md/dji）：
 *  - 骨架色取自 DJI 的工程蓝 + 中性墨阶，但整体压成**深色**（遥控器在户外强光下用，
 *    深底 + 高明度文字比白底更省眼、也更像一台仪器而不像网页）；
 *  - DJI 的核心气质是「克制」：一句话一个动作、圆角统一、阴影柔而不脏、
 *    颜色只在"状态"上出现（蓝=可交互，橙=警示，绿=正常），不做装饰性上色；
 *  - 数字统一等宽（tabular），避免电机值跳动时宽度抖动。
 *
 * 三个原则写死在实现里，改皮肤时别破坏：
 *  1) 只有一个圆角阶梯（R_SM/R_MD/R_LG/PILL），不允许各页面自己定；
 *  2) 颜色只能从下面这张表取，页面里不许出现裸 0xFF... 字面量；
 *  3) 动效只有一条缓动（easeOutCubic），时长 120~260ms。
 */
public final class Ui {

    // ======================= 色板（DJI 深色化） =======================
    /** 页面底色：比纯黑略偏蓝，避免 OLED 上"死黑"看着廉价。 */
    public static final int BG = 0xFF0A0E14;
    /** 顶栏 / 卡片底。 */
    public static final int CARD = 0xFF141A22;
    /** 卡片内嵌一层（列表行、输入框）。 */
    public static final int CARD2 = 0xFF1C242F;
    /** 抬起态（hover / 选中行）。 */
    public static final int CARD3 = 0xFF232D3A;
    /** 分隔线 / 描边。 */
    public static final int LINE = 0xFF2A3543;
    /** 正文。 */
    public static final int TEXT = 0xFFE9EEF4;
    /** 次级文字（DJI ink-secondary 的深色版）。 */
    public static final int DIM = 0xFF93A1B0;
    /** 三级文字 / 占位。 */
    public static final int FAINT = 0xFF5F6B7A;
    /** DJI 工程蓝：可交互、选中、主按钮。 */
    public static final int ACC = 0xFF2D8CF0;
    /** 蓝的暗态（按下的深度）。 */
    public static final int ACC_DIM = 0xFF1F6BC0;
    /** 蓝的淡底（选中行背景）。 */
    public static final int ACC_TINT = 0x222D8CF0;
    /** 信息蓝（次要强调，比 ACC 冷）。 */
    public static final int BLUE = 0xFF64D2FF;
    /** 警示橙（DJI tier-orange 的家用版）。 */
    public static final int WARN = 0xFFF5A524;
    /** 错误红（DJI error）。 */
    public static final int BAD = 0xFFF5522D;
    /** 正常绿。 */
    public static final int OK = 0xFF3DD68C;

    // ======================= 圆角阶梯（唯一） =======================
    public static final float R_SM = 4f;
    public static final float R_MD = 8f;
    public static final float R_LG = 12f;
    public static final float PILL = 100f;

    // ======================= 动效 =======================
    /** 快：状态切换（hover、按下）。 */
    public static final int DUR_FAST = 120;
    /** 中：页面切换、卡片展开。 */
    public static final int DUR_MID = 200;
    /** 慢：一次性入场。 */
    public static final int DUR_SLOW = 260;

    /** easeOutCubic：t∈[0,1] → [0,1]。全工程只用这一条曲线。 */
    public static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** 进度 t∈[0,1] → 0..1 的 easeOut，用于插值。 */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * ease(t);
    }

    public final float density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final Typeface cond = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    public final Typeface condBold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    public final Typeface mono = Typeface.create("monospace", Typeface.NORMAL);

    public Ui(float density) {
        this.density = density;
        tp.setTextAlign(Paint.Align.LEFT);
    }

    public float d(float v) {
        return v * density;
    }

    // ======================= 文字 =======================
    public void text(Canvas c, String s, float x, float y, float sp, int color, boolean bold) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.LEFT);
        c.drawText(s, x, y, tp);
    }

    public void textR(Canvas c, String s, float x, float y, float sp, int color, boolean bold) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, x, y, tp);
    }

    public void textC(Canvas c, String s, float cx, float y, float sp, int color, boolean bold) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, cx, y, tp);
    }

    public void monoC(Canvas c, String s, float cx, float y, float sp, int color) {
        tp.setTypeface(mono);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, cx, y, tp);
    }

    /** 等宽右对齐（数值列专用，避免数字变化时左右跳）。 */
    public void monoR(Canvas c, String s, float x, float y, float sp, int color) {
        tp.setTypeface(mono);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, x, y, tp);
    }

    /** 字距：DJI 的标题都带轻微负字距，工程感就来自这里。 */
    public void textTracked(Canvas c, String s, float x, float y, float sp, int color,
                            boolean bold, float trackingEm) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setLetterSpacing(trackingEm);
        c.drawText(s, x, y, tp);
        tp.setLetterSpacing(0f);
    }

    public float monoW(String s, float sp) {
        tp.setTypeface(mono);
        tp.setTextSize(d(sp));
        return tp.measureText(s);
    }

    public float w(String s, float sp, boolean bold) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        return tp.measureText(s);
    }

    // ======================= 面 =======================
    public void card(Canvas c, RectF b, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setShader(null);
        c.drawRoundRect(b, d(R_MD), d(R_MD), p);
    }

    /** 指定圆角的卡片（用于顶部只圆上两角这类场景）。 */
    public void cardR(Canvas c, RectF b, float r, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setShader(null);
        c.drawRoundRect(b, d(r), d(r), p);
    }

    public void cardStroke(Canvas c, RectF b, int color, float widthDp) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d(widthDp));
        p.setColor(color);
        p.setShader(null);
        c.drawRoundRect(b, d(R_MD), d(R_MD), p);
    }

    public void cardStrokeR(Canvas c, RectF b, float r, int color, float widthDp) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d(widthDp));
        p.setColor(color);
        p.setShader(null);
        c.drawRoundRect(b, d(r), d(r), p);
    }

    /**
     * 抬起态卡片：底层一层柔和阴影 + 上层填充。
     * DJI 的深度靠产品图自己带的光，我们没图，就用「贴地阴影」代替——
     * 关键是阴影必须有偏移和模糊，零偏移的彩色光晕是装饰不是深度。
     */
    public void cardRaised(Canvas c, RectF b, int color) {
        RectF sh = new RectF(b);
        sh.offset(0, d(2));
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33000000);
        p.setShader(null);
        c.drawRoundRect(sh, d(R_MD), d(R_MD), p);
        card(c, b, color);
    }

    /** 垂直渐变面（同色系由亮到暗，不做彩色渐变）。 */
    public void cardVGrad(Canvas c, RectF b, float r, int top, int bottom) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(b, d(r), d(r), p);
        p.setShader(null);
    }

    public void fill(Canvas c, RectF b, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setShader(null);
        c.drawRect(b, p);
    }

    /** 顶部状态条这类窄条的渐变（深→更深）。 */
    public void fillVGrad(Canvas c, RectF b, int top, int bottom) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(b, p);
        p.setShader(null);
    }

    public void circle(Canvas c, float cx, float cy, float r, int color, boolean stroke, float wdp) {
        p.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        p.setStrokeWidth(d(wdp));
        p.setColor(color);
        p.setShader(null);
        c.drawCircle(cx, cy, r, p);
    }

    public void line(Canvas c, float x1, float y1, float x2, float y2, int color, float wdp) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d(wdp));
        p.setColor(color);
        p.setShader(null);
        c.drawLine(x1, y1, x2, y2, p);
    }

    /** 水平分隔线（带内缩，比通栏细线更像设计而不是边框）。 */
    public void divider(Canvas c, RectF b, float y, int color) {
        line(c, b.left, y, b.right, y, color, 1f);
    }

    /**
     * 进度条：底槽 + 从中心向两侧填充的填充块。
     * 传 unit∈[-1,1]，负向往左、正向往右，中位给一个小圆点。
     */
    public void bar(Canvas c, RectF b, float unit, int fillColor) {
        cardR(c, b, b.height() / d(2f), CARD2);
        float mid = b.centerX();
        float half = b.width() / 2f;
        if (unit > 1f) unit = 1f;
        if (unit < -1f) unit = -1f;
        float w = Math.abs(unit) * half;
        if (w < d(1.5f)) {
            // 中位：一个小圆点，告诉用户"这是零点"
            circle(c, mid, b.centerY(), d(1.6f), FAINT, false, 0f);
            return;
        }
        RectF f = new RectF(unit >= 0 ? mid : mid - w, b.top, unit >= 0 ? mid + w : mid, b.bottom);
        cardR(c, f, f.height() / d(2f), fillColor);
    }

    /**
     * 单侧进度条（0..1 从左往右），用于"已用量 / 容量"这种。
     */
    public void barLeft(Canvas c, RectF b, float frac, int fillColor) {
        cardR(c, b, b.height() / d(2f), CARD2);
        if (frac < 0f) frac = 0f;
        if (frac > 1f) frac = 1f;
        float w = frac * b.width();
        if (w < d(1f)) return;
        RectF f = new RectF(b.left, b.top, b.left + w, b.bottom);
        cardR(c, f, f.height() / d(2f), fillColor);
    }

    /**
     * 状态点：带一圈柔光（用两层圆模拟，不引入 BlurMaskFilter —— 那玩意在低端 GPU 上很贵）。
     * 遥控器端每秒重绘几十次，性能比精致重要。
     */
    public void dot(Canvas c, float cx, float cy, float r, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor((color & 0x00FFFFFF) | 0x33000000);
        c.drawCircle(cx, cy, r * 2.1f, p);
        p.setColor(color);
        c.drawCircle(cx, cy, r, p);
    }

    /**
     * 主按钮（DJI 的 pill）：填充 = 主色，文字反色；
     * 描边 = 同色 1px，文字主色。圆角一律 PILL。
     */
    public void button(Canvas c, RectF b, String label, int color, boolean filled, float sp) {
        if (filled) {
            cardR(c, b, R_MD, color);
            textC(c, label, b.centerX(), b.centerY() + d(sp) * 0.36f, sp, 0xFF0A0E14, true);
        } else {
            cardR(c, b, R_MD, CARD2);
            cardStrokeR(c, b, R_MD, color, 1f);
            textC(c, label, b.centerX(), b.centerY() + d(sp) * 0.36f, sp, color, true);
        }
    }

    /** 强调按钮：DJI 蓝实心，用于"唯一主动作"。 */
    public void buttonAccent(Canvas c, RectF b, String label, float sp) {
        cardR(c, b, PILL, ACC);
        textC(c, label, b.centerX(), b.centerY() + d(sp) * 0.36f, sp, 0xFFFFFFFF, true);
    }

    /** 标签胶囊（只用于状态，不用于装饰）。 */
    public void pill(Canvas c, RectF b, String label, int color, float sp) {
        cardR(c, b, PILL, (color & 0x00FFFFFF) | 0x22000000);
        cardStrokeR(c, b, PILL, (color & 0x00FFFFFF) | 0x66000000, 1f);
        textC(c, label, b.centerX(), b.centerY() + d(sp) * 0.36f, sp, color, true);
    }

    public static boolean in(RectF b, float x, float y) {
        return x >= b.left && x <= b.right && y >= b.top && y <= b.bottom;
    }

    /** 用 0..1 的进度把两个颜色做插值（做按下/选中过渡用）。 */
    public static int mix(int a, int b, float t) {
        float e = ease(t);
        int aa = (int) (((a >> 24) & 0xFF) + ((((b >> 24) & 0xFF) - ((a >> 24) & 0xFF)) * e));
        int ar = (int) (((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * e));
        int ag = (int) (((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * e));
        int ab = (int) ((a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * e));
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }
}
