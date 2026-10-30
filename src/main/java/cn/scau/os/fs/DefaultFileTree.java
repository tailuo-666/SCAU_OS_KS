package cn.scau.os.fs;

import cn.scau.os.exec.InstructionCodec;

import java.nio.charset.StandardCharsets;

/**
 * 首次格式化后写入的默认文件树。
 *
 * <p>课程要求"至少包含 5 个目录和 15 个文件"，并且这些文件必须是真实通过
 * 文件系统接口创建出来的（真实分配磁盘块、真实写目录项），不能是界面上的假节点。
 * 本类只调用 {@link FileSystem} 的公开方法，因此它本身就是一次完整的文件系统演练。
 *
 * <pre>
 *   \                      根目录（7 个目录项，未超过 8 个的上限）
 *   ├─ BIN\   10 个可执行文件 P01.E ~ P10.E（超过 8 个目录项，会扩展到 2 个磁盘块）
 *   ├─ DOC\   7 个文本文件
 *   ├─ DAT\   3 个文本文件
 *   ├─ SYS\   3 个系统文本文件（带系统属性）
 *   ├─ TMP\   空目录，供测试使用
 *   ├─ HLP.T  使用说明
 *   └─ RED.T  课程设计说明
 * </pre>
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：第一次格式化后自动铺好的"样板文件树"——5 个目录、15 个文件、
 *       10 个可执行程序，让老师一启动就有东西可演示。</li>
 *   <li><b>从哪读起</b>：{@link #install(cn.scau.os.fs.FileSystem)}，
 *       从上往下看它 mkdir 了哪些目录、createFile 了哪些文件。</li>
 *   <li><b>最有意思的</b>：{@code P03.E} 有 8 条指令，故意超过默认时间片 6，
 *       用来演示"时间片中断"；{@code P04.E} 三条指令都申请 A 设备，
 *       用来演示"设备竞争与排队"。</li>
 *   <li><b>易错点</b>：文件名只有 3 个字符的额度，{@code HELP} 要写成 {@code HLP}。</li>
 *   <li><b>分工</b>：成员 B 主讲；成员 A 会问"这些程序分别演示什么"。</li>
 * </ul>
 */
public final class DefaultFileTree {

    private DefaultFileTree() {
    }

    /** 安装整棵默认目录树。 */
    public static void install(FileSystem fs) {
        createDirectories(fs);
        createPrograms(fs);
        createDocuments(fs);
        createDataFiles(fs);
        createSystemFiles(fs);
        createRootFiles(fs);
    }

    private static void createDirectories(FileSystem fs) {
        fs.mkdir("\\BIN");
        fs.mkdir("\\DOC");
        fs.mkdir("\\DAT");
        fs.mkdir("\\SYS");
        fs.mkdir("\\TMP");
    }

    /**
     * 10 个可执行程序，覆盖：纯赋值、自增自减、时间片切换（>6 条指令）、
     * 单设备申请、连续两次申请同一设备、三类设备混合申请、设备竞争等场景。
     */
    private static void createPrograms(FileSystem fs) {
        addProgram(fs, "\\BIN\\P01.E", "最简单的程序：赋值后立即结束",
                "x=10\nend");

        addProgram(fs, "\\BIN\\P02.E", "赋值与自增自减，恰好 6 条指令（一个时间片）",
                "x=0\nx++\nx++\nx++\nx--\nend");

        addProgram(fs, "\\BIN\\P03.E", "8 条指令，会因时间片耗尽而被切换",
                "x=1\nx++\nx++\nx++\nx++\nx++\nx++\nend");

        addProgram(fs, "\\BIN\\P04.E", "申请一次 A 类设备，使用 3 个时间单位",
                "x=20\n!A3\nx++\nend");

        addProgram(fs, "\\BIN\\P05.E", "连续两次申请 A 类设备（A 只有 2 个实例，容易产生竞争）",
                "!A2\n!A2\nx=7\nend");

        addProgram(fs, "\\BIN\\P06.E", "越过 X 上界后回退，再申请 B 类设备",
                "x=99\nx++\nx--\n!B5\nx=0\nx++\nend");

        addProgram(fs, "\\BIN\\P07.E", "连续 4 次申请 C 类设备（C 只有 3 个实例）",
                "!C1\n!C1\n!C1\n!C1\nx=8\nend");

        addProgram(fs, "\\BIN\\P08.E", "12 条指令，会经历两次时间片切换",
                "x=3\nx++\nx++\nx--\nx++\nx++\nx--\nx++\nx++\nx--\nx++\nx++\nend");

        addProgram(fs, "\\BIN\\P09.E", "短程序，用于快速产生“已完成”记录",
                "x=42\nend");

        addProgram(fs, "\\BIN\\P10.E", "依次申请 A、B、C 三类设备",
                "!A9\n!B9\n!C9\nx=1\nx++\nend");
    }

    private static void createDocuments(FileSystem fs) {
        // 这个文件超过 64 字节，会占用 2 个磁盘块，用来演示跨块读写
        fs.createFile("\\DOC\\N01.T", utf8(
                "模拟操作系统课程设计\n"
                        + "本文件用于演示流式文件的跨块读写。\n"
                        + "每个磁盘块 64 字节，内容超过 64 字节时会自动占用多个块，\n"
                        + "这些块通过文件分配表 FAT 串成一条显式链接。\n"
                        + "你可以用 type 命令查看它，也可以用 edit 修改它。\n"),
                FileAttribute.NORMAL);

        fs.createFile("\\DOC\\N02.T", utf8("进程管理：PCB、就绪队列、阻塞队列、时间片轮转。\n"),
                FileAttribute.NORMAL);
        fs.createFile("\\DOC\\N03.T", utf8("存储管理：512 字节用户区，首次适配，动态分区。\n"),
                FileAttribute.NORMAL);
        fs.createFile("\\DOC\\N04.T", utf8("设备管理：A×2、B×3、C×3，独占设备，倒计时释放。\n"),
                FileAttribute.NORMAL);
        fs.createFile("\\DOC\\N05.T", utf8("文件管理：FAT 显式链接、树形目录、8 字节目录项。\n"),
                FileAttribute.NORMAL);
        fs.createFile("\\DOC\\N06.T", utf8("这是一个只读文件，尝试写入或删除都会被拒绝。\n"),
                FileAttribute.READ_ONLY);
        fs.createFile("\\DOC\\N07.T", utf8("ab\n"),
                FileAttribute.NORMAL);
    }

    private static void createDataFiles(FileSystem fs) {
        fs.createFile("\\DAT\\A01.T", utf8("10 20 30 40 50\n"), FileAttribute.NORMAL);
        fs.createFile("\\DAT\\A02.T", utf8("3.14 2.71 1.41\n"), FileAttribute.NORMAL);
        fs.createFile("\\DAT\\A03.T", utf8("设备 A 平均使用时长：3 个时间单位\n"), FileAttribute.NORMAL);
    }

    private static void createSystemFiles(FileSystem fs) {
        fs.createFile("\\SYS\\SYS.T", utf8("SCAU-OS 模拟操作系统 版本 1.0\n"), FileAttribute.SYSTEM);
        fs.createFile("\\SYS\\CFG.T", utf8("时间片=6\n模拟速度=500ms\n自动到达=开启\n"),
                FileAttribute.SYSTEM | FileAttribute.READ_ONLY);
        fs.createFile("\\SYS\\LOG.T", utf8("系统启动日志占位文件。\n"), FileAttribute.SYSTEM);
    }

    private static void createRootFiles(FileSystem fs) {
        fs.createFile("\\HLP.T", utf8(
                "本系统命令：help dir type create delete copy move mkdir rmdir deldir\n"
                        + "change run edit chdir format clear\n"
                        + "在下方命令框输入 help 可查看完整说明。\n"),
                FileAttribute.NORMAL);
        fs.createFile("\\RED.T", utf8(
                "SCAU-OS 是华南农业大学操作系统课程设计的模拟操作系统。\n"
                        + "它在一个进程内同时实现了文件管理、存储管理、设备管理和进程管理。\n"),
                FileAttribute.NORMAL);
    }

    /**
     * 把一个汇编程序编译成机器码写入可执行文件。
     *
     * @param description 程序的用途说明，仅用于文档与教学，不占用磁盘
     */
    private static void addProgram(FileSystem fs, String path, String description, String assembly) {
        if (description == null || description.isEmpty()) {
            throw new FsException("示例程序缺少说明：" + path);
        }
        byte[] code = InstructionCodec.assemble(assembly);
        fs.createFile(path, code, FileAttribute.EXECUTABLE);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
