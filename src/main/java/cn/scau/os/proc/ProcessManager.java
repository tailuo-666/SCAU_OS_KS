package cn.scau.os.proc;

import cn.scau.os.dev.DeviceManager;
import cn.scau.os.dev.DeviceType;
import cn.scau.os.mem.MemoryManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 进程管理：PCB 池、就绪队列、阻塞队列、等内存队列，以及四个进程控制原语。
 *
 * <p><b>在模拟操作系统中的角色</b>：它是进程管理模块的核心，负责
 * <ul>
 *   <li><b>创建</b> {@link #createProcess(String, long)}：申请空白 PCB → 申请主存 → 装入程序 → 置为就绪；</li>
 *   <li><b>撤销</b> {@link #destroy(Pcb, long)}：回收内存、释放设备、摘除队列、释放 PCB；</li>
 *   <li><b>阻塞</b> {@link #block(Pcb, String, DeviceType)}：保存现场（由 CPU 负责）、改状态、挂到阻塞队列；</li>
 *   <li><b>唤醒</b> {@link #wake(Pcb)}：从阻塞队列摘下、置为就绪、挂到就绪队列尾部；</li>
 *   <li><b>调度</b> {@link #dispatch()}：从就绪队列取一个进程运行，队列空时运行闲逛进程。</li>
 * </ul>
 *
 * <h2>状态一致性约定</h2>
 * 任一普通进程在任一时刻只属于一个容器：
 * 就绪队列 / 阻塞队列 / 等内存队列 / 正在运行 / 已完成。
 * 所有状态迁移都必须通过本类的方法完成，队列与状态标签永远同步修改。
 *
 * <h2>为什么进程管理要持有内存和设备管理器</h2>
 * 因为"创建"和"撤销"这两个原语天生要动内存和设备：不还内存的销毁不是销毁。
 * 通过构造参数注入，既保持了单一职责（本类只管进程，具体分配逻辑仍在各自模块），
 * 又保证了原语的原子性。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：进程的"人事部"：创建、撤销、阻塞、唤醒四个原语 +
 *       就绪/阻塞/等内存/完成四个队列。</li>
 *   <li><b>从哪读起</b>：{@link #createProcess(String)} → {@link #dispatch()}
 *       → {@link #preemptRunning(String)} → {@link #destroy(Pcb)}。
 *       这四条正好覆盖"生—上—下—死"。</li>
 *   <li><b>为什么有"等内存队列"</b>：进程数上限 10，但 512 字节内存可能先不够用。
 *       申请失败不杀进程，而是挂着等别人释放（{@code retryPendingProcesses}）。</li>
 *   <li><b>易错点</b>：{@code dispatch()} 在就绪队列为空时必须返回闲逛进程，
 *       否则内核会出现"没有运行进程"的空洞状态。</li>
 *   <li><b>分工</b>：成员 A 主讲；全员都需要理解状态迁移图。</li>
 * </ul>
 */
public class ProcessManager {

    /** 最多 10 个普通进程。 */
    public static final int MAX_PROCESS = 10;
    /** 已完成进程保留多少条记录给界面看。 */
    private static final int FINISHED_HISTORY = 12;

    private final Pcb[] pool = new Pcb[MAX_PROCESS];
    private final Deque<Pcb> readyQueue = new ArrayDeque<>();
    private final List<Pcb> blockedQueue = new ArrayList<>();
    private final List<Pcb> pendingQueue = new ArrayList<>();
    private final List<Pcb> finishedQueue = new ArrayList<>();
    private final Pcb idleProcess;
    private final MemoryManager memory;
    private final DeviceManager devices;
    private final ProgramLoader programLoader;

    private Pcb running;
    private int nextPid = 1;
    private int quantum = 6;

    public ProcessManager(MemoryManager memory, DeviceManager devices, ProgramLoader programLoader) {
        this.memory = memory;
        this.devices = devices;
        this.programLoader = programLoader;
        this.idleProcess = new Pcb(Pcb.IDLE_PID, "<闲逛进程>", true);
        this.idleProcess.setState(ProcessState.READY);
    }

    // ==================================================================
    // 进程创建原语
    // ==================================================================

    /**
     * 创建一个进程并尝试装入内存。
     *
     * <p>四步走：① 申请空白 PCB；② 申请主存并装入程序；③ 初始化 PCB；
     * ④ 挂入就绪队列。若主存不够，PCB 保留在"等内存队列"（状态 NEW），
     * 等别的进程释放内存后由 {@link #retryPendingProcesses()} 重试。
     *
     * @param programPath 可执行文件绝对路径
     * @param now         当前系统时钟
     * @return 新建的 PCB；PCB 已满（10 个）时返回 null
     */
    public Pcb createProcess(String programPath, long now) {
        int slot = findFreeSlot();
        if (slot < 0) {
            return null;
        }
        byte[] code = programLoader.load(programPath);
        if (code.length == 0) {
            throw new IllegalStateException("可执行文件没有任何指令：" + programPath);
        }
        Pcb pcb = new Pcb(nextPid++, programPath, false);
        pcb.setSubmitTime(now);
        pcb.setPc(0);
        pcb.setX(0);
        pcb.setSliceRemaining(0);
        pool[slot] = pcb;

        int start = memory.allocate(pcb.getPid(), code.length);
        if (start < 0) {
            pcb.setState(ProcessState.NEW);
            pcb.setWaitingForMemory(true);
            pcb.setBlockedReason("等待内存（需要 " + code.length + "B）");
            pendingQueue.add(pcb);
            return pcb;
        }
        memory.loadProgram(start, code);
        pcb.setMemoryStart(start);
        pcb.setMemorySize(code.length);
        pcb.setState(ProcessState.READY);
        readyQueue.addLast(pcb);
        return pcb;
    }

    /**
     * 为等内存的进程重试分配。
     *
     * @return 本次成功装入主存的进程个数
     */
    public int retryPendingProcesses() {
        int success = 0;
        Iterator<Pcb> iterator = pendingQueue.iterator();
        while (iterator.hasNext()) {
            Pcb pcb = iterator.next();
            byte[] code = programLoader.load(pcb.getProgramPath());
            int start = memory.allocate(pcb.getPid(), code.length);
            if (start < 0) {
                continue;
            }
            memory.loadProgram(start, code);
            pcb.setMemoryStart(start);
            pcb.setMemorySize(code.length);
            pcb.setWaitingForMemory(false);
            pcb.setBlockedReason(null);
            pcb.setState(ProcessState.READY);
            pcb.setPc(0);
            pcb.setX(0);
            readyQueue.addLast(pcb);
            iterator.remove();
            success++;
        }
        return success;
    }

    // ==================================================================
    // 进程撤销原语
    // ==================================================================

    /**
     * 撤销进程：回收内存 → 释放设备 → 从所有队列摘除 → 释放 PCB。
     * 执行 {@code end} 指令、用户手动删除进程、重置系统都走这里。
     */
    public void destroy(Pcb pcb, long now) {
        if (pcb.isIdle()) {
            return;
        }
        removeFromQueues(pcb);
        memory.release(pcb.getPid());
        devices.releaseAll(pcb.getPid());
        devices.removeWaiter(pcb.getPid());
        pcb.setState(ProcessState.DONE);
        pcb.setFinishTime(now);
        pcb.setSliceRemaining(0);
        pcb.setDeviceType(null);
        pcb.setBlockedReason(null);
        freeSlot(pcb);
        finishedQueue.add(pcb);
        while (finishedQueue.size() > FINISHED_HISTORY) {
            finishedQueue.remove(0);
        }
        if (running == pcb) {
            running = null;
        }
    }

    // ==================================================================
    // 阻塞 / 唤醒原语
    // ==================================================================

    /**
     * 阻塞当前进程。CPU 现场由调用方（CPU）先保存到 PCB，这里只负责状态与队列。
     */
    public void block(Pcb pcb, String reason, DeviceType deviceType) {
        if (pcb.getState() == ProcessState.BLOCKED) {
            return;
        }
        pcb.setState(ProcessState.BLOCKED);
        pcb.setBlockedReason(reason);
        pcb.setDeviceType(deviceType);
        pcb.setSliceRemaining(0);
        if (!blockedQueue.contains(pcb)) {
            blockedQueue.add(pcb);
        }
        if (running == pcb) {
            running = null;
        }
    }

    /**
     * 唤醒进程：从阻塞队列摘下 → 置为就绪 → 挂到就绪队列尾部。
     * 非阻塞状态调用会被忽略，防止"重复唤醒"把进程塞进两个队列。
     */
    public void wake(Pcb pcb) {
        if (pcb == null || pcb.getState() != ProcessState.BLOCKED) {
            return;
        }
        blockedQueue.remove(pcb);
        pcb.setBlockedReason(null);
        pcb.setDeviceType(null);
        pcb.setState(ProcessState.READY);
        readyQueue.addLast(pcb);
    }

    // ==================================================================
    // 进程调度
    // ==================================================================

    /**
     * 从就绪队列取一个进程投入运行；就绪队列为空时运行闲逛进程。
     *
     * @return 被选中的进程（一定非 null）
     */
    public Pcb dispatch() {
        // 从就绪队列的队首取一个进程。取不到（队列空）就让闲逛进程上 CPU——
        // 这样"当前运行进程"永远不会是 null，界面和内核都不用写一堆空判断。
        Pcb next = readyQueue.pollFirst();
        if (next == null) {
            next = idleProcess;
        } else {
            next.setSliceRemaining(quantum);   // 刚上 CPU 的进程，时间片从满值重新开始算
        }
        next.setState(ProcessState.RUNNING);
        running = next;
        return next;
    }

    /**
     * 时间片用完，把运行中的进程放回就绪队列尾部。
     * 调用前必须先把 CPU 现场保存进 PCB。
     */
    public void preemptRunning() {
        if (running == null || running.isIdle()) {
            running = null;
            return;
        }
        Pcb pcb = running;
        pcb.setState(ProcessState.READY);
        pcb.setSliceRemaining(0);
        readyQueue.addLast(pcb);
        running = null;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public Pcb getRunning() {
        return running;
    }

    public Pcb getIdleProcess() {
        return idleProcess;
    }

    public int getQuantum() {
        return quantum;
    }

    public void setQuantum(int quantum) {
        if (quantum < 1) {
            throw new IllegalArgumentException("时间片必须大于 0");
        }
        this.quantum = quantum;
    }

    /** 就绪队列（按调度顺序）。 */
    public List<Pcb> readyQueue() {
        return new ArrayList<>(readyQueue);
    }

    public int readyCount() {
        return readyQueue.size();
    }

    public boolean hasReadyProcess() {
        return !readyQueue.isEmpty();
    }

    /** 阻塞队列。 */
    public List<Pcb> blockedQueue() {
        return new ArrayList<>(blockedQueue);
    }

    /** 等待内存的进程。 */
    public List<Pcb> pendingQueue() {
        return new ArrayList<>(pendingQueue);
    }

    /** 已完成的进程（最近若干条）。 */
    public List<Pcb> finishedQueue() {
        return new ArrayList<>(finishedQueue);
    }

    /** 所有活动进程（不含闲逛进程），用于界面表格。 */
    public List<Pcb> activeProcesses() {
        List<Pcb> list = new ArrayList<>();
        for (Pcb pcb : pool) {
            if (pcb != null) {
                list.add(pcb);
            }
        }
        return list;
    }

    public Pcb find(int pid) {
        if (pid == Pcb.IDLE_PID) {
            return idleProcess;
        }
        for (Pcb pcb : pool) {
            if (pcb != null && pcb.getPid() == pid) {
                return pcb;
            }
        }
        for (Pcb pcb : finishedQueue) {
            if (pcb.getPid() == pid) {
                return pcb;
            }
        }
        return null;
    }

    public int activeCount() {
        int count = 0;
        for (Pcb pcb : pool) {
            if (pcb != null) {
                count++;
            }
        }
        return count;
    }

    public int freeSlotCount() {
        return MAX_PROCESS - activeCount();
    }

    /** 复位：清空所有队列与 PCB 池。 */
    public void reset() {
        for (int i = 0; i < pool.length; i++) {
            pool[i] = null;
        }
        readyQueue.clear();
        blockedQueue.clear();
        pendingQueue.clear();
        finishedQueue.clear();
        running = null;
        nextPid = 1;
        idleProcess.setState(ProcessState.READY);
        idleProcess.setPc(0);
        idleProcess.setX(0);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private int findFreeSlot() {
        for (int i = 0; i < pool.length; i++) {
            if (pool[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private void freeSlot(Pcb pcb) {
        for (int i = 0; i < pool.length; i++) {
            if (pool[i] == pcb) {
                pool[i] = null;
                return;
            }
        }
    }

    private void removeFromQueues(Pcb pcb) {
        readyQueue.remove(pcb);
        blockedQueue.remove(pcb);
        pendingQueue.remove(pcb);
    }
}
