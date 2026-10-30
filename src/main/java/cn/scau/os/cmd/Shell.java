package cn.scau.os.cmd;

import cn.scau.os.engine.OperatingSystem;
import cn.scau.os.exec.AssemblyException;
import cn.scau.os.exec.InstructionCodec;
import cn.scau.os.fs.DirEntry;
import cn.scau.os.fs.Fat;
import cn.scau.os.fs.FileAttribute;
import cn.scau.os.fs.FileSystem;
import cn.scau.os.fs.FsException;
import cn.scau.os.fs.OpenFile;
import cn.scau.os.fs.PathUtil;
import cn.scau.os.proc.Pcb;
import cn.scau.os.util.TextUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 用户命令接口：解析并执行键盘输入的命令。
 *
 * <p><b>在模拟操作系统中的角色</b>：课程要求的"用户接口"部分。它是一个薄壳：
 * 解析参数 → 调用文件系统 / 操作系统的公开接口 → 把结果包装成 {@link CommandResult}。
 * 命令层不直接操作磁盘块，也不直接改进程队列，所有状态变化都经过模块接口，
 * 因此命令执行和界面按钮执行走的是同一条代码路径。
 *
 * <p>路径支持绝对路径与相对路径：相对路径以 {@link #getCurrentDir()} 为基准
 * （用 {@code chdir} 切换）。分隔符 {@code \} 与 {@code /} 等价，大小写不敏感。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：命令行解释器。它把用户敲的一行字拆成"命令 + 参数"，
 *       再调用对应的模块，最后把结果包装成 {@code CommandResult}。</li>
 *   <li><b>从哪读起</b>：{@link #execute(String)} 里的 {@code switch}——
 *       一张表就能看到全部 20 多条命令的分发关系。</li>
 *   <li><b>它和后端的关系</b>：Shell 是"最薄的一层"，自己不做文件/进程算法，
 *       只做三件事：解析路径、调用、把异常翻译成人话。</li>
 *   <li><b>易错点</b>：命令参数里带反斜杠路径时，要先交给 {@code PathUtil}
 *       结合当前目录解析，不能直接拼字符串。</li>
 *   <li><b>分工</b>：成员 C 主讲；成员 A 负责解释每条命令背后的内核调用。</li>
 * </ul>
 */
public class Shell {

    private final OperatingSystem os;
    private final FileSystem fs;
    private String currentDir = PathUtil.ROOT;
    private EditorService editor;
    private ConfirmService confirm = (title, message) -> false;

    public Shell(OperatingSystem os) {
        this.os = os;
        this.fs = os.getFileSystem();
    }

    /** 指定交互式编辑器（图形界面里设置，测试环境不设置）。 */
    public void setEditor(EditorService editor) {
        this.editor = editor;
    }

    /** 指定二次确认对话框（图形界面里设置）。 */
    public void setConfirm(ConfirmService confirm) {
        this.confirm = confirm;
    }

    public String getCurrentDir() {
        return currentDir;
    }

    /** 命令行提示符，例如 {@code \BIN>}。 */
    public String getPrompt() {
        return currentDir.endsWith("\\") ? currentDir + ">" : currentDir + ">";
    }

    /** 把用户输入解析成绝对路径。 */
    public String resolvePath(String raw) {
        return PathUtil.resolve(currentDir, raw);
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 执行一行命令。
     *
     * @return 执行结果（成功/失败 + 要显示的文字）
     */
    public CommandResult execute(String line) {
        // 空行直接忽略：用户连敲两次回车不该在输出区留下东西
        if (line == null || line.trim().isEmpty()) {
            return CommandResult.ok("");
        }
        // 按空白切开："mkdir \TMP\A" → ["mkdir", "\TMP\A"]
        String[] parts = line.trim().split("\\s+");
        String command = parts[0].toLowerCase();   // 命令名不区分大小写
        try {
            return dispatch(command, parts);
        } catch (FsException e) {
            // 下面三个 catch 是同一个思路：把"技术异常"翻译成"用户能看懂的一句话"，
            // 同时往系统日志里记一条（带上当前是第几拍），方便事后复盘。
            os.getLog().error(os.getClock(), "命令失败：" + line.trim() + " → " + e.getMessage());
            return CommandResult.error("错误：" + e.getMessage());
        } catch (AssemblyException e) {
            os.getLog().error(os.getClock(), "汇编失败：" + e.getMessage());
            return CommandResult.error("汇编错误：" + e.getMessage());
        } catch (RuntimeException e) {
            os.getLog().error(os.getClock(), "命令异常：" + e.getMessage());
            return CommandResult.error("命令执行失败：" + e.getMessage());
        }
    }

    private CommandResult dispatch(String command, String[] parts) {
        switch (command) {
            case "help":
                return cmdHelp(parts);
            case "dir":
                return cmdDir(parts);
            case "ls":
                return cmdLs(parts);
            case "type":
                return cmdType(parts);
            case "create":
                return cmdCreate(parts);
            case "delete":
                return cmdDelete(parts);
            case "copy":
                return cmdCopy(parts);
            case "move":
                return cmdMove(parts);
            case "mkdir":
                return cmdMkdir(parts);
            case "rmdir":
                return cmdRmdir(parts);
            case "deldir":
                return cmdDeldir(parts);
            case "change":
                return cmdChange(parts);
            case "chdir":
                return cmdChdir(parts);
            case "edit":
                return cmdEdit(parts);
            case "run":
                return cmdRun(parts);
            case "format":
                return cmdFormat(parts);
            case "fdisk":
                return cmdFdisk();
            case "open":
                return cmdOpen(parts);
            case "close":
                return cmdClose(parts);
            case "read":
                return cmdRead(parts);
            case "ps":
                return cmdPs();
            case "clear":
                return CommandResult.clear();
            default:
                return CommandResult.error("未知命令：" + command + "（输入 help 查看全部命令）");
        }
    }

    // ==================================================================
    // 帮助
    // ==================================================================

    private CommandResult cmdHelp(String[] parts) {
        if (parts.length > 1) {
            return CommandResult.ok(helpFor(parts[1].toLowerCase()));
        }
        return CommandResult.ok(
                "================ SCAU-OS 模拟操作系统 命令帮助 ================\n"
                        + "【文件与目录】\n"
                        + "  dir    [路径]              列出目录内容，省略路径表示当前目录\n"
                        + "  type   <文件>              显示文件内容；可执行文件显示反汇编\n"
                        + "  ls     [路径]              路径是目录时列目录，是文件时显示内容\n"
                        + "  create <文件>              创建空文件，如 create \\TMP\\A.T\n"
                        + "  delete <文件>              删除文件（被打开或只读时拒绝）\n"
                        + "  copy   <源> <目标>         复制文件；目标是目录时沿用源文件名\n"
                        + "  move   <源> <目标>         移动或改名\n"
                        + "  mkdir  <目录>              创建目录\n"
                        + "  rmdir  <目录>              删除空目录（非空时报错）\n"
                        + "  deldir <目录>              递归删除目录（可删非空目录）\n"
                        + "  change <路径> <属性>       属性：readonly / system / normal / exec\n"
                        + "  chdir  [目录]              切换当前目录，chdir \\ 回到根目录\n"
                        + "  edit   <文件>              打开编辑器；.e 文件用汇编文本编辑\n"
                        + "  fdisk                      显示磁盘布局与块使用情况\n"
                        + "  format [/y]                格式化磁盘（需二次确认，/y 跳过确认）\n"
                        + "【进程与系统】\n"
                        + "  run    <可执行文件>        创建并运行进程，如 run \\BIN\\P01.E\n"
                        + "  ps                         显示进程表\n"
                        + "  open   <文件> [w]          打开文件（已打开文件表最多 5 项）\n"
                        + "  close  <文件>              关闭文件\n"
                        + "  read   <文件> [字节数]     从读指针读取内容\n"
                        + "  clear                      清空命令输出区\n"
                        + "  help   [命令]              显示帮助\n"
                        + "说明：路径用 \\ 或 / 均可，大小写不敏感；文件名 1~3 字符 + 1 位扩展名。");
    }

    private String helpFor(String command) {
        switch (command) {
            case "dir":
                return "dir [路径]\n  列出目录项。输出含名称、类型、属性、起始块、长度、占用块数。";
            case "type":
                return "type <文件>\n  显示文本文件内容；可执行文件显示单字节指令的反汇编结果。";
            case "create":
                return "create <文件>\n  创建空文件，例如 create \\TMP\\A.T。扩展名 e 表示可执行文件。";
            case "delete":
                return "delete <文件>\n  删除文件并回收其全部磁盘块。文件被打开或属性为只读时拒绝删除。";
            case "copy":
                return "copy <源> <目标>\n  复制文件；目标可以是文件名，也可以是已存在的目录。";
            case "move":
                return "move <源> <目标>\n  移动文件；同一目录内移动等同于改名（不搬动磁盘块）。";
            case "mkdir":
                return "mkdir <目录>\n  创建子目录，例如 mkdir \\TMP\\SUB。根目录最多 8 个目录项。";
            case "rmdir":
                return "rmdir <目录>\n  只能删除空目录，目录非空时报错。";
            case "deldir":
                return "deldir <目录>\n  递归删除目录及其全部内容。";
            case "change":
                return "change <路径> <属性>\n  属性：readonly 只读 / system 系统 / normal 普通 / exec 可执行。";
            case "chdir":
                return "chdir [目录]\n  切换当前目录；不带参数或 chdir \\ 回到根目录。";
            case "edit":
                return "edit <文件>\n  打开编辑器。文本文件直接编辑；可执行文件以汇编形式编辑，保存时编译成单字节指令。";
            case "run":
                return "run <可执行文件>\n  以该文件创建进程：申请 PCB → 申请内存 → 装入指令 → 进入就绪队列。";
            case "format":
                return "format [/y]\n  格式化模拟磁盘：清空全部文件与 FAT。必须二次确认，/y 表示跳过确认（脚本用）。";
            case "fdisk":
                return "fdisk\n  显示磁盘参数：256 块 × 64 字节，FAT 占用 0/1/3/4 块，根目录固定第 2 块。";
            case "ps":
                return "ps\n  显示当前运行、就绪、阻塞、等内存的进程及其 PC / X / 内存 / 时间片。";
            case "open":
                return "open <文件> [w]\n  打开文件并登记到已打开文件表；w 表示以写方式打开。最多同时打开 5 个。";
            case "read":
                return "read <文件> [字节数]\n  从读指针读取内容并显示，读指针随之前移。";
            default:
                return "没有该命令的详细帮助：" + command;
        }
    }

    // ==================================================================
    // 目录浏览
    // ==================================================================

    private CommandResult cmdDir(String[] parts) {
        String target = parts.length > 1 ? resolvePath(parts[1]) : currentDir;
        if (!fs.exists(target)) {
            throw new FsException("路径不存在：" + target);
        }
        DirEntry stat = fs.stat(target);
        if (stat != null && !stat.isDirectory() && !PathUtil.isRoot(target)) {
            return CommandResult.ok(formatFileLine(stat, target));
        }
        return CommandResult.ok(listDirectory(target));
    }

    private String listDirectory(String dirPath) {
        List<DirEntry> entries = fs.list(dirPath);
        StringBuilder sb = new StringBuilder();
        sb.append("目录 ").append(dirPath)
                .append("    块数 ").append(fs.directoryBlockCount(dirPath))
                .append(" / 容量 ").append(fs.directoryCapacity(dirPath)).append(" 项")
                .append(" / 已用 ").append(entries.size()).append(" 项\n");
        sb.append(TextUtil.pad("名称", 12))
                .append(TextUtil.pad("类型", 6))
                .append(TextUtil.pad("属性", 16))
                .append(TextUtil.padLeft("起始块", 8))
                .append(TextUtil.padLeft("长度", 8))
                .append(TextUtil.padLeft("块数", 8))
                .append('\n');
        sb.append("-".repeat(58)).append('\n');
        if (entries.isEmpty()) {
            sb.append("（空目录）\n");
        }
        for (DirEntry entry : entries) {
            sb.append(TextUtil.pad(entry.getFullName(), 12))
                    .append(TextUtil.pad(entry.isDirectory() ? "目录" : "文件", 6))
                    .append(TextUtil.pad(FileAttribute.describe(entry.getAttribute()), 16))
                    .append(TextUtil.padLeft(String.valueOf(entry.getStartBlock()), 8))
                    .append(TextUtil.padLeft(String.valueOf(entry.getLength()), 8))
                    .append(TextUtil.padLeft(String.valueOf(blockCountOf(dirPath, entry)), 8))
                    .append('\n');
        }
        sb.append("-".repeat(58)).append('\n');
        sb.append("共 ").append(entries.size()).append(" 项；磁盘剩余 ")
                .append(fs.freeBlocks()).append(" 块");
        return sb.toString();
    }

    private int blockCountOf(String dirPath, DirEntry entry) {
        if (entry.isDirectory()) {
            return fs.directoryBlockCount(PathUtil.join(dirPath, entry.getFullName()));
        }
        return Math.max(1, (entry.getLength() + 63) / 64);
    }

    private String formatFileLine(DirEntry entry, String path) {
        return "文件 " + path + "\n  属性：" + FileAttribute.describe(entry.getAttribute())
                + "\n  起始块：" + entry.getStartBlock()
                + "\n  长度：" + entry.getLength() + " 字节"
                + "\n  占用块数：" + Math.max(1, (entry.getLength() + 63) / 64);
    }

    private CommandResult cmdLs(String[] parts) {
        String target = parts.length > 1 ? resolvePath(parts[1]) : currentDir;
        if (!fs.exists(target)) {
            throw new FsException("路径不存在：" + target);
        }
        if (fs.isDirectory(target)) {
            return CommandResult.ok(listDirectory(target));
        }
        return cmdType(parts);
    }

    private CommandResult cmdType(String[] parts) {
        requireArgs(parts, 2, "type <文件>");
        String path = resolvePath(parts[1]);
        if (!fs.exists(path)) {
            throw new FsException("文件不存在：" + path);
        }
        if (fs.isDirectory(path)) {
            throw new FsException("这是目录，不能显示内容：" + path);
        }
        DirEntry entry = fs.stat(path);
        byte[] content = fs.readFile(path);
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(path).append("  ").append(content.length).append(" 字节 / ")
                .append(FileAttribute.describe(entry.getAttribute())).append(" ===\n");
        if (content.length == 0) {
            sb.append("（空文件）");
            return CommandResult.ok(sb.toString());
        }
        if (FileAttribute.isExecutable(entry.getAttribute())) {
            sb.append("反汇编（每行一条指令，左侧是字节偏移与机器码）：\n");
            for (int i = 0; i < content.length; i++) {
                int code = content[i] & 0xFF;
                sb.append(String.format("  [%02d] %02X  %s%n", i, code,
                        InstructionCodec.decode(code).getText()));
            }
        } else {
            sb.append(new String(content, StandardCharsets.UTF_8));
        }
        return CommandResult.ok(sb.toString());
    }

    // ==================================================================
    // 文件操作
    // ==================================================================

    private CommandResult cmdCreate(String[] parts) {
        requireArgs(parts, 2, "create <文件>");
        String path = resolvePath(parts[1]);
        fs.createFile(path);
        os.logFile("创建文件 " + path);
        return CommandResult.ok("已创建文件：" + path + "（0 字节，占用 1 个磁盘块）");
    }

    private CommandResult cmdDelete(String[] parts) {
        requireArgs(parts, 2, "delete <文件>");
        String path = resolvePath(parts[1]);
        fs.deleteFile(path);
        os.logFile("删除文件 " + path);
        return CommandResult.ok("已删除文件：" + path + "，磁盘块已回收");
    }

    private CommandResult cmdCopy(String[] parts) {
        requireArgs(parts, 3, "copy <源文件> <目标>");
        String src = resolvePath(parts[1]);
        String dst = resolvePath(parts[2]);
        fs.copyFile(src, dst);
        os.logFile("复制文件 " + src + " → " + dst);
        return CommandResult.ok("已复制：" + src + " → " + dst);
    }

    private CommandResult cmdMove(String[] parts) {
        requireArgs(parts, 3, "move <源文件> <目标>");
        String src = resolvePath(parts[1]);
        String dst = resolvePath(parts[2]);
        fs.moveFile(src, dst);
        os.logFile("移动文件 " + src + " → " + dst);
        return CommandResult.ok("已移动：" + src + " → " + dst);
    }

    private CommandResult cmdMkdir(String[] parts) {
        requireArgs(parts, 2, "mkdir <目录>");
        String path = resolvePath(parts[1]);
        fs.mkdir(path);
        os.logFile("创建目录 " + path);
        return CommandResult.ok("已创建目录：" + path);
    }

    private CommandResult cmdRmdir(String[] parts) {
        requireArgs(parts, 2, "rmdir <目录>");
        String path = resolvePath(parts[1]);
        fs.rmdir(path);
        os.logFile("删除空目录 " + path);
        return CommandResult.ok("已删除空目录：" + path);
    }

    private CommandResult cmdDeldir(String[] parts) {
        requireArgs(parts, 2, "deldir <目录>");
        String path = resolvePath(parts[1]);
        fs.deldir(path);
        os.logFile("递归删除目录 " + path);
        return CommandResult.ok("已递归删除目录：" + path);
    }

    private CommandResult cmdChange(String[] parts) {
        requireArgs(parts, 3, "change <路径> <属性>（readonly/system/normal/exec）");
        String path = resolvePath(parts[1]);
        int attr = FileAttribute.parse(parts[2]);
        fs.changeAttribute(path, attr);
        os.logFile("修改属性 " + path + " → " + FileAttribute.describe(fs.stat(path).getAttribute()));
        return CommandResult.ok("属性已修改：" + path + " → " + FileAttribute.describe(fs.stat(path).getAttribute()));
    }

    private CommandResult cmdChdir(String[] parts) {
        String target = parts.length > 1 ? resolvePath(parts[1]) : PathUtil.ROOT;
        if (!fs.exists(target)) {
            throw new FsException("目录不存在：" + target);
        }
        if (!fs.isDirectory(target)) {
            throw new FsException("不是目录：" + target);
        }
        currentDir = target;
        return CommandResult.ok("当前目录：" + currentDir);
    }

    private CommandResult cmdEdit(String[] parts) {
        requireArgs(parts, 2, "edit <文件>");
        String path = resolvePath(parts[1]);
        if (!fs.exists(path)) {
            throw new FsException("文件不存在：" + path);
        }
        if (fs.isDirectory(path)) {
            throw new FsException("不能用编辑器打开目录：" + path);
        }
        if (FileAttribute.isReadOnly(fs.stat(path).getAttribute())) {
            throw new FsException("文件为只读属性，不能编辑：" + path);
        }
        if (editor == null) {
            return CommandResult.error("当前环境没有图形界面，无法打开编辑器（请用图形界面执行 edit）");
        }
        if (!editor.edit(path)) {
            return CommandResult.error("编辑器打开失败：" + path);
        }
        os.logFile("编辑文件 " + path);
        return CommandResult.ok("已打开编辑器：" + path);
    }

    // ==================================================================
    // 进程与系统
    // ==================================================================

    private CommandResult cmdRun(String[] parts) {
        requireArgs(parts, 2, "run <可执行文件>");
        String path = resolvePath(parts[1]);
        Pcb pcb = os.startProcess(path);
        String state = pcb.isWaitingForMemory() ? "（内存不足，已进入等内存队列）" : "，已进入就绪队列";
        return CommandResult.ok("已创建进程 P" + pcb.getPid() + " ← " + path + state);
    }

    private CommandResult cmdFormat(String[] parts) {
        boolean force = parts.length > 1
                && (parts[1].equalsIgnoreCase("/y") || parts[1].equalsIgnoreCase("-y")
                || parts[1].equalsIgnoreCase("force"));
        if (!force) {
            boolean confirmed = confirm.confirm("格式化磁盘",
                    "格式化会清除模拟磁盘上的全部文件、目录与 FAT，\n"
                            + "并结束所有正在运行的进程。此操作不可撤销，确定继续吗？");
            if (!confirmed) {
                return CommandResult.error("已取消格式化");
            }
        }
        os.formatDisk(true);
        return CommandResult.ok("磁盘已格式化，FAT 与根目录已重建，默认文件树已重新写入");
    }

    private CommandResult cmdFdisk() {
        StringBuilder sb = new StringBuilder();
        sb.append("================ 模拟磁盘参数 ================\n");
        sb.append("  块大小      64 字节\n");
        sb.append("  块总数      ").append(Fat.ENTRY_COUNT).append("\n");
        sb.append("  总容量      ").append(Fat.ENTRY_COUNT * 64).append(" 字节\n");
        sb.append("  FAT 表项    256 项 × 1 字节 = 4 块\n");
        sb.append("  磁盘布局    块 0、1 → FAT 前 128 项\n");
        sb.append("              块 2   → 根目录（8 个目录项）\n");
        sb.append("              块 3、4 → FAT 后 128 项\n");
        sb.append("              块 5~254 → 文件与子目录数据\n");
        sb.append("              块 255 → 保留\n");
        sb.append("  --------------------------------------------\n");
        sb.append("  数据块      ").append(Fat.DATA_BLOCK_COUNT).append(" 块\n");
        sb.append("  已分配      ").append(fs.usedBlocks()).append(" 块\n");
        sb.append("  空闲        ").append(fs.freeBlocks()).append(" 块\n");
        sb.append("  已打开文件  ").append(fs.openFileCount()).append(" / 5\n");
        sb.append("  说明        课程说明要求 FAT 占 0、1 块（仅 128 字节）又要 256 项，\n");
        sb.append("              数学上不可能，故把后 128 项放在 3、4 块。\n");
        return CommandResult.ok(sb.toString());
    }

    private CommandResult cmdPs() {
        List<Pcb> active = os.getProcesses().activeProcesses();
        Pcb running = os.getProcesses().getRunning();
        StringBuilder sb = new StringBuilder();
        sb.append(TextUtil.pad("PID", 6))
                .append(TextUtil.pad("状态", 8))
                .append(TextUtil.pad("程序", 16))
                .append(TextUtil.padLeft("PC", 5))
                .append(TextUtil.padLeft("X", 5))
                .append(TextUtil.padLeft("内存", 8))
                .append(TextUtil.padLeft("时间片", 8))
                .append("  说明\n");
        if (active.isEmpty()) {
            sb.append("（没有进程，CPU 运行闲逛进程）\n");
        }
        active.sort((a, b) -> Integer.compare(a.getPid(), b.getPid()));
        for (Pcb pcb : active) {
            sb.append(TextUtil.pad("P" + pcb.getPid(), 6))
                    .append(TextUtil.pad(pcb.getState().getLabel(), 8))
                    .append(TextUtil.pad(pcb.getProgramPath(), 16))
                    .append(TextUtil.padLeft(String.valueOf(pcb.getPc()), 5))
                    .append(TextUtil.padLeft(String.valueOf(pcb.getX()), 5))
                    .append(TextUtil.padLeft(pcb.getMemorySize() + "B", 8))
                    .append(TextUtil.padLeft(String.valueOf(pcb.getSliceRemaining()), 8))
                    .append("  ").append(pcb.getBlockedReason() == null ? "" : pcb.getBlockedReason())
                    .append('\n');
        }
        sb.append("\n当前运行：").append(running == null ? "-" : running.displayName())
                .append("    系统时钟：").append(os.getClock())
                .append("    时间片：").append(os.getQuantum());
        return CommandResult.ok(sb.toString());
    }

    // ==================================================================
    // 已打开文件表
    // ==================================================================

    private CommandResult cmdOpen(String[] parts) {
        requireArgs(parts, 2, "open <文件> [w]");
        String path = resolvePath(parts[1]);
        char mode = parts.length > 2 && parts[2].equalsIgnoreCase("w")
                ? OpenFile.MODE_WRITE : OpenFile.MODE_READ;
        OpenFile openFile = fs.openFile(path, mode);
        os.logFile("打开文件 " + path + "（" + (mode == 'w' ? "写" : "读") + "）");
        return CommandResult.ok("已打开：" + openFile.getPath()
                + "  模式=" + (mode == 'w' ? "读写" : "只读")
                + "  长度=" + openFile.getLength()
                + "  已打开 " + fs.openFileCount() + "/5");
    }

    private CommandResult cmdClose(String[] parts) {
        requireArgs(parts, 2, "close <文件>");
        String path = resolvePath(parts[1]);
        fs.closeFile(path);
        os.logFile("关闭文件 " + path);
        return CommandResult.ok("已关闭：" + path + "，长度已写回目录项");
    }

    private CommandResult cmdRead(String[] parts) {
        requireArgs(parts, 2, "read <文件> [字节数]");
        String path = resolvePath(parts[1]);
        int count = Integer.MAX_VALUE;
        if (parts.length > 2) {
            try {
                count = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                throw new FsException("字节数必须是整数：" + parts[2]);
            }
        }
        byte[] data = fs.readOpenedFile(path, count);
        if (data.length == 0) {
            return CommandResult.ok("（已到文件末尾，没有读到数据）");
        }
        return CommandResult.ok("读到 " + data.length + " 字节：\n"
                + new String(data, StandardCharsets.UTF_8));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void requireArgs(String[] parts, int required, String usage) {
        if (parts.length < required) {
            throw new FsException("参数不足。用法：" + usage);
        }
    }
}
