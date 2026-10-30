package cn.scau.os.proc;

import cn.scau.os.engine.OperatingSystem;
import cn.scau.os.fs.FileAttribute;
import cn.scau.os.fs.FsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程管理模块单元测试。
 *
 * <p>全部用例关闭"随机到达"并固定随机种子，通过循环调用
 * {@link OperatingSystem#runTicks(int)} 精确复现每一个时间单位，
 * 因此断言可以精确到 PC、X、时间片余量。
 */
@DisplayName("进程管理模块")
class ProcessSchedulingTest {

    @TempDir
    Path tempDir;

    private OperatingSystem os;

    @BeforeEach
    void setUp() {
        os = new OperatingSystem(tempDir, 20261027L, false);
    }

    @AfterEach
    void tearDown() {
        os.close();
    }

    @Test
    @DisplayName("没有进程时 CPU 运行闲逛进程，时钟正常推进")
    void idleProcessRunsWhenNoReadyProcess() {
        os.runTicks(3);
        assertEquals(3, os.getClock());
        assertNotNull(os.getProcesses().getRunning());
        assertTrue(os.getProcesses().getRunning().isIdle());
        assertEquals(0, os.snapshot().all.size(), "闲逛进程不进进程表");
    }

    @Test
    @DisplayName("暂停后 tick 不推进时钟，单步可以推进")
    void pauseOnlyStopsClock() {
        os.setPaused(true);
        os.tick();
        assertEquals(0, os.getClock());
        os.stepOnce();
        assertEquals(1, os.getClock());
    }

    @Test
    @DisplayName("程序执行到 end 后撤销进程并回收内存")
    void programEndDestroysProcess() {
        Pcb pcb = os.startProcess("\\BIN\\P01.E");
        assertEquals(ProcessState.READY, pcb.getState());
        assertTrue(pcb.getMemorySize() > 0);
        assertEquals(512 - pcb.getMemorySize(), os.getMemory().freeBytes());

        os.runTicks(2);
        assertEquals(ProcessState.DONE, pcb.getState());
        assertEquals(10, pcb.getX(), "P01 执行 x=10 后 X 应为 10");
        assertEquals(1, pcb.getPc(), "end 不推进 PC，PC 仍指向 end 指令");
        assertEquals(512, os.getMemory().freeBytes(), "撤销后应全部回收");
        assertEquals(1, os.getProcesses().finishedQueue().size());
        assertEquals(0, os.getProcesses().activeCount());
    }

    @Test
    @DisplayName("8 条指令的程序在时间片 6 用完后被切换，PC 与 X 正确保存")
    void timeSlicePreemptsAfterQuantum() {
        os.setQuantum(6);
        Pcb pcb = os.startProcess("\\BIN\\P03.E");
        os.runTicks(6);

        assertEquals(6, pcb.getPc(), "执行 6 条后应停在 PC=6");
        assertEquals(6, pcb.getX(), "前 6 条指令：x=1 再自增 5 次");
        assertEquals(6, pcb.getSliceRemaining(), "被重新调度后时间片复位为 6");
        assertEquals(ProcessState.RUNNING, pcb.getState());

        os.runTicks(2);
        assertEquals(ProcessState.DONE, pcb.getState());
        assertEquals(7, pcb.getX(), "6 次自增后 X=7");
    }

    @Test
    @DisplayName("两个进程轮转：第一个用满时间片后第二个得到 CPU")
    void twoProcessesRotate() {
        os.setQuantum(6);
        Pcb first = os.startProcess("\\BIN\\P03.E");
        Pcb second = os.startProcess("\\BIN\\P09.E");

        os.runTicks(6);
        assertEquals(ProcessState.READY, first.getState(), "时间片用完应回到就绪队列");
        assertEquals(ProcessState.RUNNING, second.getState(), "第二个进程应被调度");
        assertEquals(0, second.getPc(), "第二个进程还没有执行过");

        os.runTicks(2);
        assertEquals(ProcessState.DONE, second.getState());
        assertEquals(42, second.getX(), "P09 的 x=42 应已执行");
        assertEquals(ProcessState.RUNNING, first.getState(), "第一个进程接着运行");
    }

    @Test
    @DisplayName("时间片 3 时两个进程交替推进，现场（PC/X）能正确恢复")
    void contextIsRestoredAfterSwitch() {
        os.setQuantum(3);
        Pcb first = os.startProcess("\\BIN\\P03.E");
        Pcb second = os.startProcess("\\BIN\\P03.E");

        os.runTicks(3);
        assertEquals(3, first.getPc());
        assertEquals(3, first.getX());
        assertEquals(ProcessState.RUNNING, second.getState());

        os.runTicks(4);
        // 第 7 个时间单位：first 从 PC=3、X=3 继续执行第 4 条指令
        assertEquals(4, first.getPc());
        assertEquals(4, first.getX(), "恢复现场后 X 应从 3 继续自增");
    }

    @Test
    @DisplayName("设备申请使进程阻塞，撤销进程会同时释放内存与设备")
    void deviceRequestBlocksAndKillReleasesAll() {
        Pcb pcb = os.startProcess("\\BIN\\P04.E");
        os.runTicks(2);

        assertEquals(ProcessState.BLOCKED, pcb.getState());
        assertEquals(1, os.getDevices().busyCount(cn.scau.os.dev.DeviceType.A));
        assertEquals(20, pcb.getX(), "申请设备前已执行 x=20");

        assertTrue(os.killProcess(pcb.getPid()));
        assertEquals(ProcessState.DONE, pcb.getState());
        assertEquals(0, os.getDevices().busyCount(cn.scau.os.dev.DeviceType.A), "设备应被释放");
        assertEquals(512, os.getMemory().freeBytes(), "内存应被回收");
        assertFalse(os.killProcess(pcb.getPid()), "已结束的进程不能再撤销");
    }

    @Test
    @DisplayName("人工阻塞与唤醒只对非设备阻塞的进程生效")
    void manualBlockAndResume() {
        Pcb pcb = os.startProcess("\\BIN\\P02.E");
        assertTrue(os.blockProcess(pcb.getPid(), "人工测试"));
        assertEquals(ProcessState.BLOCKED, pcb.getState());
        assertEquals("人工测试", pcb.getBlockedReason());

        assertTrue(os.resumeProcess(pcb.getPid()));
        assertEquals(ProcessState.READY, pcb.getState());
        assertFalse(os.resumeProcess(pcb.getPid()), "就绪进程不能重复唤醒");

        Pcb blockedByDevice = os.startProcess("\\BIN\\P04.E");
        // P02 还要跑完剩下的指令，P04 才会拿到 CPU 并申请设备
        os.runTicks(8);
        assertEquals(ProcessState.BLOCKED, blockedByDevice.getState());
        assertFalse(os.resumeProcess(blockedByDevice.getPid()),
                "等设备的进程必须等设备完成，不能人工唤醒");
    }

    @Test
    @DisplayName("PCB 最多 10 个，第 11 个创建失败")
    void pcbLimitIsTen() {
        for (int i = 0; i < ProcessManager.MAX_PROCESS; i++) {
            os.startProcess("\\BIN\\P01.E");
        }
        assertEquals(10, os.getProcesses().activeCount());
        FsException e = assertThrows(FsException.class, () -> os.startProcess("\\BIN\\P01.E"));
        assertTrue(e.getMessage().contains("PCB"), "提示应说明 PCB 已满");
    }

    @Test
    @DisplayName("程序大于 512 字节时进入等内存队列，不占用 CPU")
    void oversizedProgramWaitsForMemory() {
        os.getFileSystem().createFile("\\TMP\\BIG.E",
                repeated("x=1\n", 600).getBytes(StandardCharsets.UTF_8), FileAttribute.EXECUTABLE);
        Pcb pcb = os.startProcess("\\TMP\\BIG.E");

        assertTrue(pcb.isWaitingForMemory());
        assertEquals(ProcessState.NEW, pcb.getState());
        assertEquals(1, os.getProcesses().pendingQueue().size());
        assertEquals(512, os.getMemory().freeBytes());
    }

    @Test
    @DisplayName("内存被其他进程释放后，等内存的进程自动装入并转为就绪")
    void pendingProcessIsLoadedAfterRelease() {
        os.getMemory().allocate(999, 300);
        os.getFileSystem().createFile("\\TMP\\M30.E",
                repeated("x=1\n", 75).getBytes(StandardCharsets.UTF_8), FileAttribute.EXECUTABLE);

        Pcb pcb = os.startProcess("\\TMP\\M30.E");
        assertTrue(pcb.isWaitingForMemory(), "只剩 212 字节，300 字节装不下");
        assertEquals(1, os.getProcesses().pendingQueue().size());

        os.getMemory().release(999);
        os.runTicks(1);

        assertFalse(pcb.isWaitingForMemory());
        assertEquals(ProcessState.READY, pcb.getState());
        assertEquals(0, os.getProcesses().pendingQueue().size());
        assertEquals(300, pcb.getMemorySize());
        assertEquals(0, pcb.getMemoryStart());
    }

    @Test
    @DisplayName("开启随机到达后会自动创建进程")
    void randomArrivalCreatesProcesses() {
        OperatingSystem randomOs = new OperatingSystem(tempDir.resolve("random"), 7L, true);
        try {
            randomOs.runTicks(5);
            assertTrue(randomOs.getProcesses().activeCount() >= 1, "5 个时间单位内应至少到达一个进程");
            assertTrue(randomOs.getClock() >= 5);
        } finally {
            randomOs.close();
        }
    }

    @Test
    @DisplayName("重置系统清空进程、内存、设备并把时钟归零")
    void resetClearsEverything() {
        Pcb pcb = os.startProcess("\\BIN\\P04.E");
        os.runTicks(2);
        assertEquals(ProcessState.BLOCKED, pcb.getState());

        os.resetSystem();
        assertEquals(0, os.getClock());
        assertEquals(0, os.getProcesses().activeCount());
        assertEquals(512, os.getMemory().freeBytes());
        assertEquals(0, os.getDevices().totalBusy());
        assertTrue(os.getFileSystem().exists("\\BIN\\P01.E"), "重置系统不应删除磁盘文件");
    }

    private static String repeated(String text, int times) {
        StringBuilder sb = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(text);
        }
        return sb.toString();
    }
}
