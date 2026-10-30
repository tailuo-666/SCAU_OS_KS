package cn.scau.os.exec;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 指令编解码器：把可执行文件里的"汇编形式"与"单字节机器码"互相转换。
 *
 * <p><b>在模拟操作系统中的角色</b>：它是课程要求里"汇编 / 编译的模拟过程"。
 * 用户编辑可执行文件时看到的是可读的汇编文本（{@code x=10}、{@code !A3}），
 * 保存时编译成每条 1 字节的机器码写进磁盘块；进程运行时 CPU 又从磁盘读回
 * 字节并解释执行。编码规则集中在这里，其他地方不允许出现魔数。
 *
 * <h2>编码表</h2>
 * <pre>
 *    0 ~  99   赋值      x=n，n 就是 0~99 本身
 *        100   自增      x++
 *        101   自减      x--
 *        102   结束      end
 *  103 ~ 112   设备 A    !A0 ~ !A9
 *  113 ~ 122   设备 B    !B0 ~ !B9
 *  123 ~ 132   设备 C    !C0 ~ !C9
 * </pre>
 * 设备指令的通用公式：{@code 103 + 设备类别编号 × 10 + 使用时长}，
 * 其中 A / B / C 的类别编号分别是 0 / 1 / 2，使用时长为 0~9。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：汇编 ↔ 机器码的翻译器。一条指令压成 1 个字节，
 *       所以"程序文件"其实就是一串 0~132 的数字。</li>
 *   <li><b>编码规则</b>（先背下来再读代码）：<br>
 *       0~99 = {@code x=n}（数字就是 n）<br>
 *       100 = {@code x++}，101 = {@code x--}，102 = {@code end}<br>
 *       103~132 = 申请设备：{@code 103 + 类型×10 + 时长}（A/B/C = 0/1/2，时长 0~9）</li>
 *   <li><b>从哪读起</b>：{@link #assemble(String)}（文本 → 字节）→
 *       {@link #decode(int)}（字节 → 可读文本）→ {@link #disassemble(byte[])}。</li>
 *   <li><b>易错点</b>：{@code !A3} 表示"申请 A 类设备 3 个时间单位"，
 *       不是"第 3 台 A 设备"；申请设备的编码要落在 103~132 之间。</li>
 *   <li><b>分工</b>：成员 A 主讲；成员 D 会问"编辑器里保存时发生了什么"。</li>
 * </ul>
 */
public final class InstructionCodec {

    /** 赋值指令的上界（0~99 都是赋值）。 */
    public static final int ASSIGN_MAX = 99;
    /** 自增。 */
    public static final int CODE_INCREMENT = 100;
    /** 自减。 */
    public static final int CODE_DECREMENT = 101;
    /** 结束。 */
    public static final int CODE_END = 102;
    /** 设备指令起点。 */
    public static final int CODE_DEVICE_BASE = 103;
    /** 设备指令终点。 */
    public static final int CODE_DEVICE_MAX = 132;

    /** A 类设备的类别编号。 */
    public static final int CATEGORY_A = 0;
    public static final int CATEGORY_B = 1;
    public static final int CATEGORY_C = 2;

    /** 设备使用时长允许的最大值。 */
    public static final int MAX_DEVICE_DURATION = 9;
    /** 赋值指令允许的最大值。 */
    public static final int MAX_ASSIGN_VALUE = 99;

    private InstructionCodec() {
    }

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    /** 赋值指令 x=n 编码成 1 字节（n 直接就是机器码）。 */
    public static byte encodeAssign(int value) {
        if (value < 0 || value > MAX_ASSIGN_VALUE) {
            throw new AssemblyException(0, "赋值只能是 0~99，实际为 " + value);
        }
        return (byte) value;
    }

    /** 设备指令编码：103 + 类别 × 10 + 时长。 */
    public static byte encodeDevice(char deviceName, int duration) {
        int category = categoryOf(deviceName);
        if (duration < 0 || duration > MAX_DEVICE_DURATION) {
            throw new AssemblyException(0, "设备使用时长只能是 0~9，实际为 " + duration);
        }
        return (byte) (CODE_DEVICE_BASE + category * 10 + duration);
    }

    /** 设备字母对应的类别编号。 */
    public static int categoryOf(char deviceName) {
        switch (Character.toUpperCase(deviceName)) {
            case 'A':
                return CATEGORY_A;
            case 'B':
                return CATEGORY_B;
            case 'C':
                return CATEGORY_C;
            default:
                throw new AssemblyException(0, "未知设备：" + deviceName + "（只有 A / B / C）");
        }
    }

    /** 类别编号对应的设备字母。 */
    public static char deviceOfCategory(int category) {
        switch (category) {
            case CATEGORY_A:
                return 'A';
            case CATEGORY_B:
                return 'B';
            case CATEGORY_C:
                return 'C';
            default:
                throw new AssemblyException(0, "未知设备类别编号：" + category);
        }
    }

    // ------------------------------------------------------------------
    // 解码
    // ------------------------------------------------------------------

    /**
     * 解码一个字节。
     * <p>注意：Java 的 byte 是有符号的，必须先 {@code & 0xFF} 转成无符号数，
     * 否则 103 以上的编码会变成负数，直接判断出错。
     */
    public static Decoded decode(int rawByte) {
        int code = rawByte & 0xFF;
        if (code <= ASSIGN_MAX) {
            return new Decoded(Decoded.Kind.ASSIGN, code, (char) 0, 0, "x=" + code);
        }
        if (code == CODE_INCREMENT) {
            return new Decoded(Decoded.Kind.INCREMENT, 0, (char) 0, 0, "x++");
        }
        if (code == CODE_DECREMENT) {
            return new Decoded(Decoded.Kind.DECREMENT, 0, (char) 0, 0, "x--");
        }
        if (code == CODE_END) {
            return new Decoded(Decoded.Kind.END, 0, (char) 0, 0, "end");
        }
        if (code >= CODE_DEVICE_BASE && code <= CODE_DEVICE_MAX) {
            int offset = code - CODE_DEVICE_BASE;
            int category = offset / 10;
            int duration = offset % 10;
            char device = deviceOfCategory(category);
            return new Decoded(Decoded.Kind.DEVICE, 0, device, duration, "!" + device + duration);
        }
        return new Decoded(Decoded.Kind.INVALID, code, (char) 0, 0, "非法指令(" + code + ")");
    }

    /** 解码一个字节（byte 入参版本）。 */
    public static Decoded decode(byte rawByte) {
        return decode(rawByte & 0xFF);
    }

    // ------------------------------------------------------------------
    // 汇编 / 反汇编
    // ------------------------------------------------------------------

    /**
     * 把汇编文本编译成指令字节数组。
     *
     * <p>支持的写法（大小写不敏感，允许空格，空行与 {@code ;} {@code #} 开头为注释）：
     * <pre>
     *   x=10      x = 10
     *   x++
     *   x--
     *   !A3       !a 3
     *   end
     * </pre>
     */
    public static byte[] assemble(String source) {
        if (source == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] lines = source.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            out.write(assembleLine(line, i + 1));
        }
        return out.toByteArray();
    }

    /** 编译单条指令。 */
    public static byte assembleLine(String line, int lineNumber) {
        String text = line.replace(" ", "").replace("\t", "").toLowerCase(Locale.ROOT);
        if (text.equals("x++")) {
            return (byte) CODE_INCREMENT;
        }
        if (text.equals("x--")) {
            return (byte) CODE_DECREMENT;
        }
        if (text.equals("end")) {
            return (byte) CODE_END;
        }
        if (text.startsWith("x=")) {
            String number = text.substring(2);
            try {
                int value = Integer.parseInt(number);
                if (value < 0 || value > MAX_ASSIGN_VALUE) {
                    throw new AssemblyException(lineNumber, "赋值只能是 0~99，实际为 " + value);
                }
                return (byte) value;
            } catch (NumberFormatException e) {
                throw new AssemblyException(lineNumber, "无法识别的赋值语句：" + line);
            }
        }
        if (text.startsWith("!")) {
            String body = text.substring(1);
            if (body.length() != 2) {
                throw new AssemblyException(lineNumber, "设备指令格式应为 !A3（设备字母 + 一位时长）：" + line);
            }
            char device = body.charAt(0);
            int category;
            try {
                category = categoryOf(device);
            } catch (AssemblyException e) {
                throw new AssemblyException(lineNumber, e.getMessage());
            }
            char durationChar = body.charAt(1);
            if (!Character.isDigit(durationChar)) {
                throw new AssemblyException(lineNumber, "设备使用时长必须是 0~9 的数字：" + line);
            }
            int duration = durationChar - '0';
            return (byte) (CODE_DEVICE_BASE + category * 10 + duration);
        }
        throw new AssemblyException(lineNumber, "无法识别的指令：" + line
                + "（只支持 x=n / x++ / x-- / !A3 / end）");
    }

    /** 把指令字节反汇编成可读文本，一行一条。 */
    public static List<String> disassemble(byte[] code) {
        List<String> lines = new ArrayList<>();
        if (code == null) {
            return lines;
        }
        for (byte b : code) {
            lines.add(decode(b).getText());
        }
        return lines;
    }

    /** 把指令字节拼成多行汇编文本（编辑可执行文件时用）。 */
    public static String disassembleToText(byte[] code) {
        return String.join("\n", disassemble(code));
    }

    // ------------------------------------------------------------------
    // 解码结果
    // ------------------------------------------------------------------

    /** 一条已解码的指令。 */
    public static final class Decoded {

        /** 指令种类。 */
        public enum Kind {
            ASSIGN, INCREMENT, DECREMENT, DEVICE, END, INVALID
        }

        private final Kind kind;
        private final int value;
        private final char device;
        private final int duration;
        private final String text;

        Decoded(Kind kind, int value, char device, int duration, String text) {
            this.kind = kind;
            this.value = value;
            this.device = device;
            this.duration = duration;
            this.text = text;
        }

        public Kind getKind() {
            return kind;
        }

        /** 赋值指令的数值。 */
        public int getValue() {
            return value;
        }

        /** 设备指令的设备名（A/B/C）。 */
        public char getDevice() {
            return device;
        }

        /** 设备指令的使用时长。 */
        public int getDuration() {
            return duration;
        }

        public String getText() {
            return text;
        }

        public boolean isInvalid() {
            return kind == Kind.INVALID;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
