package cn.scau.os.cmd;

import cn.scau.os.engine.OperatingSystem;
import cn.scau.os.fs.FileAttribute;
import cn.scau.os.proc.ProcessState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户命令模块单元测试。
 *
 * <p>命令层的意义在于"业务逻辑不依赖界面"：这里没有创建任何 Swing 组件，
 * 一样可以验证每条命令的行为。测试通过 {@link CommandResult} 判断成功与否，
 * 再回到 {@link OperatingSystem} 检查真实状态有没有变化。
 */
@DisplayName("用户命令模块")
class ShellTest {

    @TempDir
    Path tempDir;

    private OperatingSystem os;
    private Shell shell;

    @BeforeEach
    void setUp() {
        os = new OperatingSystem(tempDir, 1L, false);
        shell = new Shell(os);
    }

    @AfterEach
    void tearDown() {
        os.close();
    }

    private CommandResult run(String line) {
        return shell.execute(line);
    }

    @Test
    @DisplayName("help 列出全部命令，help <命令> 给出单条说明")
    void helpCommand() {
        CommandResult all = run("help");
        assertTrue(all.isSuccess());
        assertTrue(all.getMessage().contains("SCAU-OS"));
        for (String cmd : new String[]{"dir", "type", "create", "delete", "copy", "move",
                "mkdir", "rmdir", "deldir", "change", "chdir", "edit", "run", "format", "fdisk"}) {
            assertTrue(all.getMessage().contains(cmd), "帮助中应包含 " + cmd);
        }

        CommandResult one = run("help dir");
        assertTrue(one.getMessage().contains("列出目录项"));
    }

    @Test
    @DisplayName("dir / ls 能列出目录，也能显示单个文件的信息")
    void browseCommands() {
        CommandResult root = run("dir");
        assertTrue(root.isSuccess());
        assertTrue(root.getMessage().contains("BIN"));
        assertTrue(root.getMessage().contains("7 项"), "根目录应有 7 个目录项");

        CommandResult bin = run("dir \\BIN");
        assertTrue(bin.getMessage().contains("P01.E"));
        assertTrue(bin.getMessage().contains("10 项"));

        CommandResult file = run("dir \\DOC\\N01.T");
        assertTrue(file.getMessage().contains("294 字节"));

        CommandResult ls = run("ls \\DOC");
        assertTrue(ls.getMessage().contains("N07.T"));
    }

    @Test
    @DisplayName("type 显示文本内容，可执行文件显示反汇编")
    void typeCommand() {
        CommandResult text = run("type \\DOC\\N07.T");
        assertTrue(text.isSuccess());
        assertTrue(text.getMessage().contains("ab"));

        CommandResult program = run("type \\BIN\\P01.E");
        assertTrue(program.isSuccess());
        assertTrue(program.getMessage().contains("x=10"), "可执行文件应显示反汇编");
        assertTrue(program.getMessage().contains("end"));
    }

    @Test
    @DisplayName("create / mkdir / copy / move / delete / deldir 组合使用")
    void fileCommands() {
        assertTrue(run("create \\TMP\\A.T").isSuccess());
        assertTrue(os.getFileSystem().exists("\\TMP\\A.T"));

        assertTrue(run("mkdir \\TMP\\SUB").isSuccess());
        assertTrue(os.getFileSystem().isDirectory("\\TMP\\SUB"));

        assertTrue(run("copy \\DOC\\N07.T \\TMP\\C.T").isSuccess());
        assertEquals("ab\n", os.getFileSystem().readText("\\TMP\\C.T"));

        assertTrue(run("move \\TMP\\C.T \\TMP\\SUB\\D.T").isSuccess());
        assertTrue(os.getFileSystem().exists("\\TMP\\SUB\\D.T"));

        assertTrue(run("delete \\TMP\\A.T").isSuccess());
        assertFalse(os.getFileSystem().exists("\\TMP\\A.T"));

        assertTrue(run("deldir \\TMP").isSuccess());
        assertFalse(os.getFileSystem().exists("\\TMP"));
    }

    @Test
    @DisplayName("chdir 后相对路径生效，提示符随之变化")
    void relativePathsAfterChdir() {
        assertTrue(run("chdir \\DOC").isSuccess());
        assertEquals("\\DOC", shell.getCurrentDir());
        assertEquals("\\DOC>", shell.getPrompt());

        assertTrue(run("create NEW.T").isSuccess());
        assertTrue(os.getFileSystem().exists("\\DOC\\NEW.T"), "相对路径应基于当前目录");

        assertTrue(run("chdir \\").isSuccess());
        assertEquals("\\", shell.getCurrentDir());
        assertFalse(run("chdir \\NOPE").isSuccess());
    }

    @Test
    @DisplayName("错误命令与非法操作返回失败，不会破坏系统")
    void errorHandling() {
        CommandResult unknown = run("foobar");
        assertFalse(unknown.isSuccess());
        assertTrue(unknown.getMessage().contains("未知命令"));

        CommandResult rmdir = run("rmdir \\DOC");
        assertFalse(rmdir.isSuccess(), "非空目录不能用 rmdir 删除");
        assertTrue(os.getFileSystem().exists("\\DOC\\N01.T"));

        CommandResult type = run("type \\DOC");
        assertFalse(type.isSuccess(), "目录不能用 type 显示");

        CommandResult missing = run("type \\NOPE.T");
        assertFalse(missing.isSuccess());
    }

    @Test
    @DisplayName("只读文件的修改与删除被拒绝，改为普通后可操作")
    void attributeCommands() {
        assertFalse(run("delete \\DOC\\N06.T").isSuccess(), "只读文件不能删除");

        assertTrue(run("change \\DOC\\N06.T normal").isSuccess());
        assertEquals(FileAttribute.NORMAL,
                os.getFileSystem().stat("\\DOC\\N06.T").getAttribute() & FileAttribute.READ_ONLY);
        assertTrue(run("delete \\DOC\\N06.T").isSuccess());
    }

    @Test
    @DisplayName("run 创建进程，ps 能看到它，时钟推进后正常结束")
    void processCommands() {
        CommandResult started = run("run \\BIN\\P01.E");
        assertTrue(started.isSuccess());
        assertTrue(started.getMessage().contains("P1"));

        CommandResult ps = run("ps");
        assertTrue(ps.getMessage().contains("P1"));
        assertTrue(ps.getMessage().contains("P01.E"));

        os.runTicks(2);
        assertEquals(ProcessState.DONE, os.getProcesses().finishedQueue().get(0).getState());
        assertTrue(run("ps").getMessage().contains("没有进程"));
    }

    @Test
    @DisplayName("run 不存在的程序或非可执行文件时报错")
    void runRejectsInvalidProgram() {
        assertFalse(run("run \\BIN\\NONE.E").isSuccess());
        assertFalse(run("run \\DOC\\N01.T").isSuccess(), "文本文件不是可执行文件");
        assertFalse(run("run \\BIN").isSuccess(), "目录不能运行");
    }

    @Test
    @DisplayName("open / read / close 走已打开文件表，最多 5 个")
    void openFileCommands() {
        assertTrue(run("open \\DOC\\N01.T").isSuccess());
        CommandResult read = run("read \\DOC\\N01.T 30");
        assertTrue(read.isSuccess());
        assertTrue(read.getMessage().contains("模拟操作系统课程"));

        assertTrue(run("close \\DOC\\N01.T").isSuccess());
        assertFalse(run("read \\DOC\\N01.T 10").isSuccess(), "关闭后不能再读");

        String[] paths = {"\\DOC\\N01.T", "\\DOC\\N02.T", "\\DOC\\N03.T",
                "\\DOC\\N04.T", "\\DOC\\N05.T", "\\DOC\\N07.T"};
        for (int i = 0; i < 5; i++) {
            assertTrue(run("open " + paths[i]).isSuccess(), "第 " + (i + 1) + " 个应能打开");
        }
        assertFalse(run("open " + paths[5]).isSuccess(), "第 6 个应被拒绝");
    }

    @Test
    @DisplayName("fdisk 显示的磁盘布局与实际常量一致")
    void fdiskCommand() {
        CommandResult result = run("fdisk");
        assertTrue(result.isSuccess());
        String text = result.getMessage();
        assertTrue(text.contains("64 字节"));
        assertTrue(text.contains("256"));
        assertTrue(text.contains("16384"));
        assertTrue(text.contains("块 2"));
        assertTrue(text.contains("250"));
        assertTrue(text.contains("数学上不可能"), "应说明课程规格矛盾的处理方式");
    }

    @Test
    @DisplayName("format 默认需要确认；确认后重建默认文件树")
    void formatRequiresConfirmation() {
        assertTrue(run("create \\TMP\\TEP.T").isSuccess());

        CommandResult cancelled = run("format");
        assertFalse(cancelled.isSuccess());
        assertTrue(cancelled.getMessage().contains("取消"));
        assertTrue(os.getFileSystem().exists("\\TMP\\TEP.T"), "未确认时不应格式化");

        shell.setConfirm((title, message) -> true);
        CommandResult done = run("format");
        assertTrue(done.isSuccess());
        assertFalse(os.getFileSystem().exists("\\TMP\\TEP.T"), "格式化后旧文件应消失");
        assertTrue(os.getFileSystem().exists("\\BIN\\P01.E"), "默认文件树应被重新安装");
        assertEquals(0, os.getClock(), "格式化会重置系统时钟");
    }

    @Test
    @DisplayName("clear 返回清屏标记，空命令不报错")
    void miscCommands() {
        assertTrue(run("clear").isClearScreen());
        assertTrue(run("").isSuccess());
        assertTrue(run("   ").isSuccess());
    }
}
