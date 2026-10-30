package cn.scau.os.fs;

/**
 * 目录项 / 文件的属性定义。
 *
 * <p>目录项的第 5 个字节是"属性字节"，用位标志表示，因此一个文件可以同时是
 * "只读 + 系统文件"。课程要求至少支持：只读文件、系统文件、普通可读写文件、目录项，
 * 本项目再补一个"可执行"标志，方便界面区分 .e 文件。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：把"只读/系统/目录/可执行"四种属性压进 1 个字节的位运算工具类。</li>
 *   <li><b>从哪读起</b>：{@link #describe(int)}（给界面看的文字）与
 *       {@link #parse(String)}（把 {@code chmod} 风格的输入解析成掩码）。</li>
 *   <li><b>前置知识</b>：位与 {@code &}、位或 {@code |}。例如
 *       {@code attr | READ_ONLY} 就是"加上只读属性"。</li>
 *   <li><b>分工</b>：成员 B 主讲。</li>
 * </ul>
 */
public final class FileAttribute {

    /** 只读文件：不允许写入、删除、改名。 */
    public static final int READ_ONLY = 0x01;
    /** 系统文件：界面单独标色，普通用户目录里也能存在。 */
    public static final int SYSTEM = 0x02;
    /** 目录项。 */
    public static final int DIRECTORY = 0x04;
    /** 可执行文件（扩展名为 e）。 */
    public static final int EXECUTABLE = 0x08;

    /** 普通可读写文件：属性字节为 0。 */
    public static final int NORMAL = 0x00;

    private FileAttribute() {
    }

    public static boolean isReadOnly(int attr) {
        return (attr & READ_ONLY) != 0;
    }

    public static boolean isSystem(int attr) {
        return (attr & SYSTEM) != 0;
    }

    public static boolean isDirectory(int attr) {
        return (attr & DIRECTORY) != 0;
    }

    public static boolean isExecutable(int attr) {
        return (attr & EXECUTABLE) != 0;
    }

    /** 中文描述，用于界面与命令输出，例如 "目录 系统"、"普通"、"只读 可执行"。 */
    public static String describe(int attr) {
        StringBuilder sb = new StringBuilder();
        if (isDirectory(attr)) {
            sb.append("目录");
        } else {
            sb.append(isReadOnly(attr) ? "只读" : "可读写");
            if (isExecutable(attr)) {
                sb.append(" 可执行");
            }
        }
        if (isSystem(attr)) {
            sb.append(" 系统");
        }
        return sb.toString();
    }

    /**
     * 解析用户输入的属性名。
     *
     * @param text readonly / system / normal / exec / dir
     * @return 对应的属性字节
     */
    public static int parse(String text) {
        String key = text == null ? "" : text.trim().toLowerCase();
        switch (key) {
            case "readonly":
            case "r":
            case "只读":
                return READ_ONLY;
            case "system":
            case "s":
            case "系统":
                return SYSTEM;
            case "exec":
            case "e":
                return EXECUTABLE;
            case "dir":
                return DIRECTORY;
            case "normal":
            case "n":
            case "普通":
                return NORMAL;
            default:
                throw new FsException("未知的属性：" + text + "（可用：readonly / system / normal / exec）");
        }
    }

    /** 清理属性：普通文件不能带"目录"标志，避免出现半文件半目录的怪状态。 */
    public static int sanitizeForFile(int attr) {
        return attr & ~DIRECTORY;
    }
}
