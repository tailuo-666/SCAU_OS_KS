package cn.scau.os.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

/**
 * 界面主题：颜色、字体、通用边框集中在这里。
 *
 * <p>把配色抽出来有两个好处：一是各面板风格统一，二是要换主题只改这一个文件。
 * 整体是浅色卡片风格：浅灰底 + 白色卡片 + 蓝色强调色。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：所有颜色、字体、边框的集中定义。改这里就能换整站风格。</li>
 *   <li><b>从哪读起</b>：{@link #install()}（设置全局字体与抗锯齿）→
 *       {@link #pickFont(boolean, int, int)}（为什么中英文要用两套字体）。</li>
 *   <li><b>易错点</b>：等宽字体 Consolas 没有中文字形，直接用它显示中文会变成方块。
 *       所以"要显示中文的地方"用 {@code CONSOLE_FONT_NAME}，纯数字/寄存器才用等宽字体。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public final class Theme {

    // ---- 基础色 ----
    public static final Color BACKGROUND = new Color(0xF2F4F8);
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(0xDDE3EC);
    public static final Color TEXT = new Color(0x1F2937);
    public static final Color MUTED = new Color(0x6B7280);
    public static final Color ACCENT = new Color(0x2563EB);
    public static final Color ACCENT_LIGHT = new Color(0xDBEAFE);

    // ---- 语义色 ----
    public static final Color SUCCESS = new Color(0x15A34A);
    public static final Color WARNING = new Color(0xD97706);
    public static final Color DANGER = new Color(0xDC2626);
    public static final Color PURPLE = new Color(0x7C3AED);
    public static final Color TEAL = new Color(0x0D9488);

    // ---- 进程状态色 ----
    public static final Color STATE_NEW_BG = new Color(0xEDE9FE);
    public static final Color STATE_NEW_FG = new Color(0x6D28D9);
    public static final Color STATE_READY_BG = new Color(0xDBEAFE);
    public static final Color STATE_READY_FG = new Color(0x1D4ED8);
    public static final Color STATE_RUNNING_BG = new Color(0xDCFCE7);
    public static final Color STATE_RUNNING_FG = new Color(0x15803D);
    public static final Color STATE_BLOCKED_BG = new Color(0xFEF3C7);
    public static final Color STATE_BLOCKED_FG = new Color(0xB45309);
    public static final Color STATE_DONE_BG = new Color(0xE5E7EB);
    public static final Color STATE_DONE_FG = new Color(0x4B5563);

    // ---- 内存/磁盘配色 ----
    /** 进程分区色板，按 pid 取模循环使用。 */
    public static final Color[] PROCESS_COLORS = {
            new Color(0x3B82F6), new Color(0x10B981), new Color(0xF59E0B),
            new Color(0x8B5CF6), new Color(0xEC4899), new Color(0x14B8A6),
            new Color(0xF97316), new Color(0x6366F1), new Color(0x84CC16),
            new Color(0x06B6D4)
    };
    public static final Color BLOCK_FREE = new Color(0xE8EDF5);
    public static final Color BLOCK_FAT = new Color(0x94A3B8);
    public static final Color BLOCK_ROOT = new Color(0x7C3AED);
    public static final Color BLOCK_RESERVED = new Color(0xCBD5E1);
    public static final Color BLOCK_USED = new Color(0x3B82F6);

    // ---- 字体 ----
    /**
     * 界面字体：优先中文字体，保证中文不出现方框。
     * 注意 getAvailableFontFamilyNames() 在中文 Windows 上返回的是本地化名称，
     * 所以中英文名都要列出来。
     */
    public static final String UI_FONT_NAME = pickFont(false, "Microsoft YaHei", "微软雅黑",
            "Microsoft YaHei UI", "PingFang SC", "SansSerif");

    /** 等宽字体（只用于纯 ASCII：数字、寄存器、十六进制）。 */
    public static final String MONO_FONT_NAME = pickFont(false, "Consolas", "Cascadia Mono", "Monospaced");

    /**
     * 控制台字体：既要等宽又要能显示中文。
     * Consolas 这类纯拉丁字体画中文会变成方框，所以这里单独挑一个中文字体。
     */
    public static final String CONSOLE_FONT_NAME = pickFont(true, "Sarasa Mono SC", "NSimSun", "新宋体",
            "Microsoft YaHei", "微软雅黑", "Dialog");

    public static final Font FONT_BASE = new Font(UI_FONT_NAME, Font.PLAIN, 13);
    public static final Font FONT_SMALL = new Font(UI_FONT_NAME, Font.PLAIN, 12);
    public static final Font FONT_BOLD = new Font(UI_FONT_NAME, Font.BOLD, 13);
    public static final Font FONT_TITLE = new Font(UI_FONT_NAME, Font.BOLD, 15);
    public static final Font FONT_HUGE = new Font(UI_FONT_NAME, Font.BOLD, 20);
    /** 纯 ASCII 的等宽字体。 */
    public static final Font FONT_MONO = new Font(MONO_FONT_NAME, Font.PLAIN, 12);
    public static final Font FONT_MONO_BOLD = new Font(MONO_FONT_NAME, Font.BOLD, 13);
    /** 含中文的控制台字体。 */
    public static final Font FONT_CONSOLE = new Font(CONSOLE_FONT_NAME, Font.PLAIN, 12);

    private Theme() {
    }

    /**
     * 挑选系统里真实存在的第一个字体。
     *
     * @param needChinese 是否要求能显示中文（等宽拉丁字体未必有中文字形）
     */
    private static String pickFont(boolean needChinese, String... candidates) {
        for (String candidate : candidates) {
            Font font = new Font(candidate, Font.PLAIN, 12);
            // 字体不存在时 Java 会回退到 Dialog，用家族名是否相同来判断
            if (!font.getFamily().equalsIgnoreCase(candidate)) {
                continue;
            }
            if (needChinese && font.canDisplayUpTo("进程管理") != -1) {
                continue;
            }
            return candidate;
        }
        return needChinese ? "Dialog" : "SansSerif";
    }

    /** 安装外观：系统风格 + 统一字体。 */
    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // 拿不到系统外观就用默认的，不影响功能
            System.err.println("无法安装系统外观，使用默认外观：" + e.getMessage());
        }
        Font base = FONT_BASE;
        UIManager.put("Label.font", base);
        UIManager.put("Button.font", base);
        UIManager.put("CheckBox.font", base);
        UIManager.put("RadioButton.font", base);
        UIManager.put("ComboBox.font", base);
        UIManager.put("Spinner.font", base);
        UIManager.put("TextField.font", base);
        UIManager.put("TextArea.font", base);
        UIManager.put("Table.font", base);
        UIManager.put("TableHeader.font", FONT_BOLD);
        UIManager.put("Tree.font", base);
        UIManager.put("TabbedPane.font", base);
        UIManager.put("TitledBorder.font", FONT_BOLD);
        UIManager.put("ToolTip.font", FONT_SMALL);
        UIManager.put("Table.rowHeight", 26);
        UIManager.put("Table.showGrid", Boolean.FALSE);
        UIManager.put("Table.selectionBackground", ACCENT_LIGHT);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("TableHeader.background", new Color(0xF8FAFC));
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("OptionPane.messageFont", base);
        UIManager.put("OptionPane.buttonFont", base);
    }

    /** 进程颜色，pid 决定，稳定不跳色。 */
    public static Color processColor(int pid) {
        if (pid <= 0) {
            return new Color(0xB6BEC9);
        }
        return PROCESS_COLORS[(pid - 1) % PROCESS_COLORS.length];
    }

    /** 浅色版本（用于背景填充）。 */
    public static Color lighter(Color color, float ratio) {
        int r = Math.min(255, (int) (color.getRed() + (255 - color.getRed()) * ratio));
        int g = Math.min(255, (int) (color.getGreen() + (255 - color.getGreen()) * ratio));
        int b = Math.min(255, (int) (color.getBlue() + (255 - color.getBlue()) * ratio));
        return new Color(r, g, b);
    }

    /** 状态前景色。 */
    public static Color stateForeground(String state) {
        switch (state) {
            case "新建":
                return STATE_NEW_FG;
            case "就绪":
                return STATE_READY_FG;
            case "运行":
                return STATE_RUNNING_FG;
            case "阻塞":
                return STATE_BLOCKED_FG;
            default:
                return STATE_DONE_FG;
        }
    }

    /** 状态背景色。 */
    public static Color stateBackground(String state) {
        switch (state) {
            case "新建":
                return STATE_NEW_BG;
            case "就绪":
                return STATE_READY_BG;
            case "运行":
                return STATE_RUNNING_BG;
            case "阻塞":
                return STATE_BLOCKED_BG;
            default:
                return STATE_DONE_BG;
        }
    }

    /** 统一的卡片外边框。 */
    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    /** 紧凑按钮：缩小内边距。 */
    public static void compact(javax.swing.AbstractButton button) {
        button.setFont(FONT_SMALL);
        button.setMargin(new Insets(3, 10, 3, 10));
        button.setFocusPainted(false);
    }

    /** 小号说明文字。 */
    public static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_SMALL);
        label.setForeground(MUTED);
        return label;
    }

    /** 强调数字。 */
    public static JLabel value(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_MONO_BOLD);
        label.setForeground(TEXT);
        return label;
    }

    /** 让组件透明，便于叠在卡片背景上。 */
    public static void transparent(JComponent... components) {
        for (JComponent component : components) {
            component.setOpaque(false);
        }
    }
}
