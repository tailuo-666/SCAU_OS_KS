package cn.scau.os.util;

/**
 * 文本排版小工具。
 *
 * <p>控制台表格要"看起来对齐"，但一个汉字在等宽字体里占两个字符宽度，
 * 直接用 {@code String.format("%-10s")} 会错位。这里按东亚字符宽度计算显示宽度，
 * 命令输出和界面表格都用它对齐。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：让终端风格的中文表格能对齐的小工具。</li>
 *   <li><b>为什么需要</b>：一个汉字在屏幕上占两格，{@code String.length()} 却算 1，
 *       直接补空格会全部错位。</li>
 *   <li><b>从哪读起</b>：{@link #displayWidth(String)} → {@link #pad(String, int)}。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public final class TextUtil {

    private TextUtil() {
    }

    /** 字符串在等宽字体下的显示宽度：中文/全角算 2，其余算 1。 */
    public static int width(String text) {
        if (text == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += isWide(text.charAt(i)) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    /** 左对齐补空格到指定显示宽度。 */
    public static String pad(String text, int width) {
        String value = text == null ? "" : text;
        int pad = width - width(value);
        if (pad <= 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value);
        for (int i = 0; i < pad; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** 右对齐（数字列用）。 */
    public static String padLeft(String text, int width) {
        String value = text == null ? "" : text;
        int pad = width - width(value);
        if (pad <= 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pad; i++) {
            sb.append(' ');
        }
        return sb.append(value).toString();
    }

    /** 字节转十六进制，如 {@code 0A 64 67}。 */
    public static String hex(byte[] data, int limit) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, data.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        if (limit < data.length) {
            sb.append(" ...");
        }
        return sb.toString();
    }
}
