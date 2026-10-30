package cn.scau.os.engine;

import cn.scau.os.dev.DeviceType;
import cn.scau.os.exec.InstructionCodec;
import cn.scau.os.fs.FileAttribute;
import cn.scau.os.fs.FsException;
import cn.scau.os.proc.Pcb;
import cn.scau.os.proc.ProcessState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 综合测试：把文件、内存、设备、进程四个模块放在一起跑真实场景。
 *
 * <p>这些用例模拟的是"答辩时会被要求现场演示"的几条路径，
 * 断言只依赖对外可见的状态（进程状态、块数、设备占用、日志），
 * 不使用任何界面组件。
 */
@DisplayName("综合场景")
class IntegratedScenarioTest {

    @TempDir
    Path tempDir;

    private OperatingSystem os;

    @BeforeEach
    void setUp() {
        os = new OperatingSystem(tempDir, 42L, false);
    }

    @AfterEach
    void tearDown() {
        os.close();
    }

    @Test
    @DisplayName("场景一：新建目录与文件 → 写入 → 跨块读取 → 复制 → 删除，块数收支平衡")
    void fileLifecycle() {
        var fs = os.getFileSystem();
        int base = fs.freeBlocks();

        fs.mkdir("\\TMP\\WRK");
        assertTrue(fs.isDirectory("\\TMP\\WRK"));
        int afterMkdir = fs.freeBlocks();
        assertEquals(base - 1, afterMkdir, "新建目录本身占 1 块");

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("第").append(i).append("行：模拟操作系统课程设计\n");
        }
        byte[] content = text.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(content.length > 128, "内容应超过两个磁盘块");
        fs.createFile("\\TMP\\WRK\\LOG.T", content, FileAttribute.NORMAL);

        int used = afterMkdir - fs.freeBlocks();
        assertEquals((content.length + 63) / 64, used, "占用块数应与内容长度匹配");
        assertEquals(text.toString(), fs.readText("\\TMP\\WRK\\LOG.T"), "跨块读取应完全一致");

        fs.copyFile("\\TMP\\WRK\\LOG.T", "\\TMP\\WRK\\BAK.T");
        assertEquals(afterMkdir - 2 * used, fs.freeBlocks());

        fs.deleteFile("\\TMP\\WRK\\BAK.T");
        fs.deleteFile("\\TMP\\WRK\\LOG.T");
        fs.rmdir("\\TMP\\WRK");
        assertEquals(base, fs.freeBlocks(), "全部删除后磁盘应完全恢复");
    }

    @Test
    @DisplayName("场景二：进程装入主存后即使源文件被删除也能正常执行")
    void runningProcessSurvivesSourceDeletion() {
        Pcb pcb = os.startProcess("\\BIN\\P03.E");
        os.runTicks(2);
        assertEquals(ProcessState.RUNNING, pcb.getState());

        os.getFileSystem().deleteFile("\\BIN\\P03.E");
        assertFalse(os.getFileSystem().exists("\\BIN\\P03.E"));

        os.runTicks(10);
        assertEquals(ProcessState.DONE, pcb.getState(), "指令已在内存中，删除源文件不影响执行");
        assertEquals(7, pcb.getX());
    }

    @Test
    @DisplayName("场景三：三个进程竞争 2 台 A 类设备 → 一个排队 → 依次完成")
    void threeProcessesCompeteForTwoDevices() {
        // 专门写一个"长时间占用 A 类设备"的程序：!A9 会占用 9 个时间单位，
        // 远长于前两个进程提出申请的时间，因此第三个进程必然进入等待队列。
        byte[] code = InstructionCodec.assemble("!A9\nx=1\nend");
        os.getFileSystem().createFile("\\TMP\\D9.E", code, FileAttribute.EXECUTABLE);

        Pcb p1 = os.startProcess("\\TMP\\D9.E");
        Pcb p2 = os.startProcess("\\TMP\\D9.E");
        Pcb p3 = os.startProcess("\\TMP\\D9.E");

        os.runTicks(3);
        assertEquals(2, os.getDevices().busyCount(DeviceType.A), "两台 A 设备都应被占用");
        assertEquals(1, os.getDevices().waitQueueOf(DeviceType.A).size(), "第三个进程应在排队");
        assertEquals(ProcessState.BLOCKED, p3.getState());
        assertTrue(p3.getBlockedReason().contains("等待"), "阻塞原因应说明在等设备");

        os.runTicks(60);
        assertEquals(ProcessState.DONE, p1.getState());
        assertEquals(ProcessState.DONE, p2.getState());
        assertEquals(ProcessState.DONE, p3.getState());
        assertEquals(1, p1.getX(), "D9 申请设备后执行 x=1");
        assertEquals(0, os.getDevices().totalBusy(), "全部完成后设备应归还");
        assertEquals(512, os.getMemory().freeBytes(), "全部完成后内存应归还");
        assertEquals(3, os.getProcesses().finishedQueue().size());
        assertTrue(os.getDevices().waitQueueOf(DeviceType.A).isEmpty(), "等待队列应被清空");
    }

    @Test
    @DisplayName("场景四：时间片轮转让两个 8 条指令的程序交替推进")
    void roundRobinMakesProgressOnBoth() {
        os.setQuantum(3);
        Pcb a = os.startProcess("\\BIN\\P08.E");
        Pcb b = os.startProcess("\\BIN\\P08.E");

        os.runTicks(3);
        assertEquals(3, a.getPc());
        assertEquals(0, b.getPc());
        assertEquals(ProcessState.RUNNING, b.getState());

        os.runTicks(3);
        assertEquals(3, b.getPc(), "B 也用完了 3 个时间单位");
        assertEquals(3, a.getPc(), "A 的 PC 停在 3 没有被重置");

        os.runTicks(40);
        assertEquals(ProcessState.DONE, a.getState());
        assertEquals(ProcessState.DONE, b.getState());
        // P08: x=3, 8 次自增, 3 次自减 → 3 + 8 - 3 = 8
        assertEquals(8, a.getX());
        assertEquals(8, b.getX());
    }

    @Test
    @DisplayName("场景五：磁盘写满后创建文件失败，删除大文件后恢复")
    void diskFullIsRecoverable() {
        var fs = os.getFileSystem();
        int free = fs.freeBlocks();
        fs.createFile("\\TMP\\FUL.T", new byte[free * 64], FileAttribute.NORMAL);
        assertEquals(0, fs.freeBlocks());

        FsException e = assertThrows(FsException.class, () -> fs.createFile("\\TMP\\X.T"));
        assertTrue(e.getMessage().contains("空间不足"));

        Pcb pcb = os.startProcess("\\BIN\\P01.E");
        os.runTicks(2);
        assertEquals(ProcessState.DONE, pcb.getState(), "磁盘满不影响已在内存中的进程");

        fs.deleteFile("\\TMP\\FUL.T");
        assertEquals(free, fs.freeBlocks());
        fs.createFile("\\TMP\\X.T");
        assertTrue(fs.exists("\\TMP\\X.T"));
    }

    @Test
    @DisplayName("场景六：快照中的进程表、内存、设备、磁盘数据互相自洽")
    void snapshotIsConsistent() {
        os.startProcess("\\BIN\\P04.E");
        os.startProcess("\\BIN\\P03.E");
        os.runTicks(2);

        SystemSnapshot s = os.snapshot();
        int running = (s.running != null && !s.running.idle()) ? 1 : 0;
        assertEquals(s.all.size(), s.ready.size() + s.blocked.size() + s.pending.size() + running,
                "进程总表应等于各队列之和");
        assertEquals(250, s.freeBlocks + s.usedBlocks, "空闲块 + 已用块 = 数据区 250 块");
        assertEquals(250, s.dataBlocks);
        assertEquals(8, s.deviceTotal);
        assertEquals(512, s.memoryTotal);
        assertTrue(s.memoryUsed >= 0 && s.memoryUsed <= 512);
        assertNotNull(s.tree);
        assertFalse(s.logs.isEmpty(), "运行过程中应产生日志");
        assertNotNull(s.running);
    }

    @Test
    @DisplayName("场景七：关闭系统后重新打开，磁盘内容与文件系统结构保持不变")
    void persistenceAcrossSystemRestart() {
        Path workDir = tempDir.resolve("restart");
        OperatingSystem first = new OperatingSystem(workDir, 1L, false);
        byte[] text = "重启后仍然存在\n第二行".getBytes(StandardCharsets.UTF_8);
        first.getFileSystem().createFile("\\TMP\\KEP.T", text, FileAttribute.NORMAL);
        String content = first.getFileSystem().readText("\\TMP\\KEP.T");
        first.getFileSystem().mkdir("\\TMP\\SUB");
        first.getFileSystem().createFile("\\TMP\\SUB\\A.T",
                InstructionCodec.assemble("x=5\nend"), FileAttribute.EXECUTABLE);
        first.close();

        OperatingSystem second = new OperatingSystem(workDir, 1L, false);
        try {
            assertEquals(content, second.getFileSystem().readText("\\TMP\\KEP.T"));
            assertTrue(second.getFileSystem().isDirectory("\\TMP\\SUB"));
            assertEquals(2, second.getFileSystem().readFile("\\TMP\\SUB\\A.T").length);
            assertEquals(10, second.getFileSystem().list("\\BIN").size(), "默认程序应全部存在");

            List<String> programs = second.availablePrograms();
            assertTrue(programs.contains("\\BIN\\P01.E"));
            assertEquals(10, programs.size());
        } finally {
            second.close();
        }
    }
}
