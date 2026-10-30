package cn.scau.os.engine;

import cn.scau.os.dev.Device;
import cn.scau.os.dev.DeviceManager;
import cn.scau.os.dev.DeviceRequest;
import cn.scau.os.dev.DeviceType;
import cn.scau.os.exec.Cpu;
import cn.scau.os.exec.CpuOutcome;
import cn.scau.os.fs.DirEntry;
import cn.scau.os.fs.Fat;
import cn.scau.os.fs.FileAttribute;
import cn.scau.os.fs.FileSystem;
import cn.scau.os.fs.FsException;
import cn.scau.os.fs.OpenFile;
import cn.scau.os.fs.PathUtil;
import cn.scau.os.mem.MemoryManager;
import cn.scau.os.proc.Pcb;
import cn.scau.os.proc.ProcessManager;
import cn.scau.os.proc.ProcessState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 模拟操作系统内核：把文件、内存、设备、进程、CPU 五个模块粘合成一台"机器"。
 *
 * <h2>统一模拟时钟</h2>
 * 整个系统只有一个时间来源 {@link #tick()}。每次调用推进一个逻辑时间单位，
 * 并按固定顺序完成下列工作（顺序即文档中描述的顺序，测试也依赖它）：
 * <pre>
 *   ① 系统时钟 +1
 *   ② 设备倒计时 -1；归零的设备释放、唤醒原进程、并把设备交给等待队列队首
 *   ③ 若没有运行进程，则调度一个（就绪队列空则运行闲逛进程）
 *   ④ 执行一条 CPU 指令（可能引起设备申请 → 阻塞，或 end → 撤销）
 *   ⑤ 若该进程仍在运行，剩余时间片 -1；减到 0 则触发时间片中断并切换
 *   ⑥ 为"等内存"的进程重试分配
 *   ⑦ 随机到达：按倒计时随机创建一个新进程
 * </pre>
 *
 * <p>界面用 {@code javax.swing.Timer} 每 N 毫秒调用一次 {@code tick()}，
 * 并且只有一个定时器，因此不存在"多个定时器各扣一次倒计时"的问题。
 * 测试里直接循环调用 {@code tick()} 即可复现任意时刻的状态。
 *
 * <p>暂停（{@link #setPaused(boolean)}）只阻止时钟推进，不影响查看与命令操作；
 * {@link #stepOnce()} 可以在暂停状态下精确推进一个时间单位。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：整个项目的"大脑"。它自己不实现任何算法，
 *       只是按正确顺序调用五个模块，让它们看起来像一台真的机器。</li>
 *   <li><b>从哪读起</b>：只读 {@link #tick()} 一个方法就够了，其余都是它的配套。
 *       tick 里那 7 个步骤的注释，就是整份课程设计的核心答案。</li>
 *   <li><b>为什么只有一个时钟</b>：如果界面、进程、设备各有一个定时器，
 *       就可能出现"设备倒计时跑了两拍、进程只跑了一拍"的错乱。
 *       统一时钟是"可复现"的前提，测试才能断言"第 6 拍一定发生时间片中断"。</li>
 *   <li><b>易错点</b>：tick 的 7 步顺序不能随手调换，
 *       例如"调度"必须在"执行指令"之前，否则第一拍没有进程在跑。</li>
 *   <li><b>分工</b>：成员 A 主讲，这是全组最该背下来的一段代码。</li>
 * </ul>
 */
public class OperatingSystem implements AutoCloseable {

    /** 默认时间片长度。 */
    public static final int DEFAULT_QUANTUM = 6;
    /** 默认每个逻辑时间单位对应的真实毫秒数。 */
    public static final int DEFAULT_TICK_INTERVAL_MS = 500;
    /** 随机到达的最小间隔（时间单位）。 */
    public static final int MIN_ARRIVAL_INTERVAL = 2;
    /** 随机到达的最大间隔（时间单位）。 */
    public static final int MAX_ARRIVAL_INTERVAL = 5;
    /** 磁盘镜像文件名。 */
    public static final String DISK_IMAGE_NAME = "disk.img";

    private final Path workDirectory;
    private final FileSystem fileSystem;
    private final MemoryManager memory = new MemoryManager();
    private final DeviceManager devices = new DeviceManager();
    private final ProcessManager processes;
    private final Cpu cpu = new Cpu();
    private final EventLog log = new EventLog();
    private final Random random;

    private long clock;
    private boolean paused;
    private boolean autoArrival = true;
    private int arrivalCountdown;
    private int tickIntervalMs = DEFAULT_TICK_INTERVAL_MS;
    private List<String> programCache;

    public OperatingSystem(Path workDirectory) {
        this(workDirectory, System.nanoTime(), true);
    }

    /**
     * @param workDirectory 工作目录，磁盘镜像 disk.img 会放在这里
     * @param seed          随机数种子（测试时固定种子可复现）
     */
    public OperatingSystem(Path workDirectory, long seed, boolean autoArrival) {
        this.workDirectory = workDirectory.toAbsolutePath();
        this.fileSystem = FileSystem.open(this.workDirectory.resolve(DISK_IMAGE_NAME));
        this.processes = new ProcessManager(memory, devices, fileSystem::readFile);
        this.processes.setQuantum(DEFAULT_QUANTUM);
        this.random = new Random(seed);
        this.autoArrival = autoArrival;
        this.arrivalCountdown = 1;          // 第一次到达很快，方便演示
        log.system(0, "系统启动，磁盘镜像：" + this.workDirectory.resolve(DISK_IMAGE_NAME));
        log.system(0, "用户区 " + MemoryManager.USER_MEMORY_SIZE + " 字节，时间片 "
                + DEFAULT_QUANTUM + " 个时间单位，设备 A×2 B×3 C×3");
    }

    // ==================================================================
    // 统一时钟
    // ==================================================================

    /** 推进一步。暂停时什么都不做。 */
    public void tick() {
        if (paused) {
            return;
        }
        tickInternal();
    }

    /** 无论是否暂停都推进一步（"单步运行"按钮用）。 */
    public void stepOnce() {
        tickInternal();
    }

    /** 连续推进 n 步（测试用）。 */
    public void runTicks(int n) {
        for (int i = 0; i < n; i++) {
            tickInternal();
        }
    }

    private void tickInternal() {
        clock++;                      // ① 系统时钟

        handleDeviceCountdown();      // ② 设备倒计时与 I/O 中断

        if (processes.getRunning() == null) {
            dispatchNext();           // ③ 没有运行进程就调度
        }

        // 闲逛进程正在占 CPU，但已有就绪进程 → 立刻让出。
        // 为什么要多这一步：如果只在"没有运行进程"时才调度，闲逛进程会一直霸着 CPU
        // 直到有人把它踢下去，用户点了"运行"却看不到反应，会以为程序卡了。
        Pcb running = processes.getRunning();
        if (running != null && running.isIdle() && processes.hasReadyProcess()) {
            processes.preemptRunning();
            dispatchNext();
            running = processes.getRunning();
        }

        if (running != null && !running.isIdle()) {
            executeOneInstruction(running);     // ④ 执行一条指令
            Pcb after = processes.getRunning();
            if (after == running) {
                // ⑤ 进程没有阻塞也没有结束，扣时间片
                running.addRunTime();
                int left = running.getSliceRemaining() - 1;
                running.setSliceRemaining(Math.max(0, left));
                if (left <= 0) {
                    handleTimeSliceInterrupt();
                }
            }
        } else if (running != null) {
            running.addRunTime();     // 闲逛进程也要消耗时间
        }

        processes.retryPendingProcesses();      // ⑥ 等内存的进程再试试
        handleRandomArrival();                  // ⑦ 随机进程到达
    }

    // ==================================================================
    // CPU 指令执行
    // ==================================================================

    private void executeOneInstruction(Pcb pcb) {
        CpuOutcome outcome = cpu.executeStep(memory, pcb);
        cpu.saveTo(pcb);              // 现场随时同步，进程被撤销时也不会丢 PC
        pcb.setLastResult(outcome.toString());

        switch (outcome.getKind()) {
            case VALUE_UPDATED:
                log.process(clock, "P" + pcb.getPid() + " 执行 " + outcome.getInstruction()
                        + "，X=" + outcome.getXValue()
                        + (outcome.getNote().isEmpty() ? "" : "（" + outcome.getNote() + "）"));
                break;
            case DEVICE_REQUEST:
                handleDeviceRequest(pcb, outcome);
                break;
            case END:
                handleProgramEnd(pcb, outcome);
                break;
            case INVALID:
            default:
                log.error(clock, "P" + pcb.getPid() + " 执行非法指令："
                        + outcome.getInstruction() + "，异常结束");
                processes.destroy(pcb, clock);
                cpu.reset();
                dispatchNext();
                break;
        }
    }

    /**
     * 设备申请指令的处理。
     *
     * <p>有设备就分配并阻塞（设备开始倒计时），没设备就进等待队列。
     * 两种情况进程都进入阻塞状态，必须立即调度别的进程占用 CPU。
     */
    private void handleDeviceRequest(Pcb pcb, CpuOutcome outcome) {
        DeviceType type = outcome.getDeviceType();
        int duration = outcome.getDuration();
        boolean allocated = devices.request(type, pcb.getPid(), duration);
        if (allocated) {
            log.device(clock, "P" + pcb.getPid() + " 申请 " + type.getLabel() + " 类设备成功，使用 "
                    + duration + " 个时间单位，进程转入阻塞");
            pcb.setDeviceType(type);
            processes.block(pcb, "占用" + type.getLabel() + "类设备（剩 " + duration + "）", type);
        } else {
            devices.enqueue(type, pcb.getPid(), duration);
            log.device(clock, "P" + pcb.getPid() + " 申请 " + type.getLabel()
                    + " 类设备失败（无空闲实例），进入等待队列，排队 "
                    + devices.waitQueueOf(type).size() + " 个");
            pcb.setDeviceType(type);
            processes.block(pcb, "等待" + type.getLabel() + "类设备", type);
        }
        dispatchNext();
    }

    /** end 指令：撤销进程。 */
    private void handleProgramEnd(Pcb pcb, CpuOutcome outcome) {
        log.process(clock, "程序结束中断：P" + pcb.getPid() + " 执行 end，最终 X="
                + outcome.getXValue() + "，撤销进程并回收资源");
        processes.destroy(pcb, clock);
        cpu.reset();
        dispatchNext();
    }

    /** 时间片中断：保存现场 → 放回就绪队列尾部 → 重新调度。 */
    private void handleTimeSliceInterrupt() {
        Pcb pcb = processes.getRunning();
        if (pcb == null || pcb.isIdle()) {
            return;
        }
        cpu.saveTo(pcb);
        pcb.setSliceRemaining(0);
        log.process(clock, "时间片中断：P" + pcb.getPid() + " 用完 " + processes.getQuantum()
                + " 个时间单位，保存现场 PC=" + pcb.getPc() + " X=" + pcb.getX() + "，重新调度");
        processes.preemptRunning();
        dispatchNext();
    }

    // ==================================================================
    // 设备倒计时
    // ==================================================================

    private void handleDeviceCountdown() {
        List<Device> finished = devices.tickDevices();
        for (Device device : finished) {
            int ownerPid = device.getOwnerPid();
            devices.release(device);
            log.device(clock, "I/O 中断：设备 " + device.getId() + " 使用完毕，释放");

            Pcb owner = processes.find(ownerPid);
            if (owner != null && owner.getState() == ProcessState.BLOCKED) {
                processes.wake(owner);
                log.process(clock, "唤醒 P" + ownerPid + "（由阻塞转为就绪）");
            }
            handDeviceToNextWaiter(device);
        }
    }

    /**
     * 把刚释放的设备直接交给等待队列队首。
     * 队首进程可能已经被撤销（队列里的"幽灵"），这时跳过并继续看下一个。
     */
    private void handDeviceToNextWaiter(Device device) {
        while (true) {
            DeviceRequest request = devices.dispatchToNext(device);
            if (request == null) {
                return;
            }
            Pcb waiter = processes.find(request.pid());
            if (waiter == null || waiter.getState() != ProcessState.BLOCKED) {
                device.release();          // 幽灵请求：立刻收回，继续找下一个
                continue;
            }
            log.device(clock, "设备 " + device.getId() + " 直接分配给等待队列队首 P"
                    + request.pid() + "，使用 " + request.duration() + " 个时间单位");
            waiter.setBlockedReason("占用" + device.getType().getLabel() + "类设备（剩 "
                    + request.duration() + "）");
            return;
        }
    }

    // ==================================================================
    // 调度
    // ==================================================================

    private void dispatchNext() {
        Pcb next = processes.dispatch();
        cpu.restoreFrom(next);
        if (next.isIdle()) {
            log.process(clock, "就绪队列为空，CPU 运行闲逛进程");
        } else {
            log.process(clock, "调度：P" + next.getPid() + " 占用 CPU（PC=" + next.getPc()
                    + "，时间片 " + processes.getQuantum() + "）");
        }
    }

    // ==================================================================
    // 随机进程到达
    // ==================================================================

    private void handleRandomArrival() {
        if (!autoArrival) {
            return;
        }
        if (arrivalCountdown > 0) {
            arrivalCountdown--;
            if (arrivalCountdown > 0) {
                return;
            }
        }
        if (processes.activeCount() >= ProcessManager.MAX_PROCESS) {
            arrivalCountdown = randomArrivalInterval();   // PCB 已满，等下一轮
            return;
        }
        List<String> programs = availablePrograms();
        if (programs.isEmpty()) {
            arrivalCountdown = randomArrivalInterval();
            return;
        }
        String path = programs.get(random.nextInt(programs.size()));
        arrivalCountdown = randomArrivalInterval();
        try {
            Pcb pcb = startProcess(path);
            log.process(clock, "随机到达：新进程 " + pcb.displayName() + " 由 " + path + " 创建");
        } catch (FsException e) {
            log.error(clock, "随机到达失败：" + e.getMessage());
        }
    }

    private int randomArrivalInterval() {
        return MIN_ARRIVAL_INTERVAL
                + random.nextInt(MAX_ARRIVAL_INTERVAL - MIN_ARRIVAL_INTERVAL + 1);
    }

    // ==================================================================
    // 对外操作：创建 / 撤销 / 阻塞 / 唤醒
    // ==================================================================

    /**
     * 用指定的可执行文件创建一个进程。
     *
     * @throws FsException 文件不存在、不是可执行文件、PCB 已满
     */
    public Pcb startProcess(String programPath) {
        String path = PathUtil.normalize(programPath);
        if (!fileSystem.exists(path)) {
            throw new FsException("可执行文件不存在：" + path);
        }
        DirEntry entry = fileSystem.stat(path);
        if (entry == null || entry.isDirectory()) {
            throw new FsException("不是可执行文件：" + path);
        }
        if (!FileAttribute.isExecutable(entry.getAttribute())) {
            throw new FsException("该文件不是可执行文件（扩展名应为 .e）：" + path);
        }
        if (entry.getLength() == 0) {
            throw new FsException("可执行文件没有指令：" + path);
        }
        Pcb pcb = processes.createProcess(path, clock);
        if (pcb == null) {
            throw new FsException("PCB 已满（最多 " + ProcessManager.MAX_PROCESS + " 个进程），请先结束其他进程");
        }
        if (pcb.isWaitingForMemory()) {
            log.memory(clock, "P" + pcb.getPid() + " 内存不足（需要 " + entry.getLength()
                    + "B，最大空闲 " + memory.largestFreeBlock() + "B），进入等内存队列");
        } else {
            log.memory(clock, "P" + pcb.getPid() + " 分配内存 " + pcb.getMemorySize() + "B @ "
                    + pcb.getMemoryStart() + "，装入 " + path);
            log.process(clock, "创建进程 P" + pcb.getPid() + "（" + path + "），进入就绪队列");
        }
        return pcb;
    }

    /** 手动撤销进程（界面右键"删除进程"）。 */
    public boolean killProcess(int pid) {
        Pcb pcb = processes.find(pid);
        if (pcb == null || pcb.isIdle()) {
            return false;
        }
        if (pcb.getState() == ProcessState.DONE) {
            return false;
        }
        cpu.saveTo(pcb);
        log.process(clock, "手动撤销进程 P" + pid + "，回收内存与设备");
        boolean wasRunning = processes.getRunning() == pcb;
        processes.destroy(pcb, clock);
        if (wasRunning) {
            cpu.reset();
            dispatchNext();
        }
        return true;
    }

    /** 手动阻塞进程（界面右键"人工阻塞"）。 */
    public boolean blockProcess(int pid, String reason) {
        Pcb pcb = processes.find(pid);
        if (pcb == null || pcb.isIdle()) {
            return false;
        }
        if (pcb.getState() != ProcessState.READY && pcb.getState() != ProcessState.RUNNING) {
            return false;
        }
        boolean wasRunning = processes.getRunning() == pcb;
        if (wasRunning) {
            cpu.saveTo(pcb);
            processes.preemptRunning();
        }
        processes.block(pcb, reason == null ? "人工阻塞" : reason, null);
        log.process(clock, "人工阻塞 P" + pid + "，原因：" + pcb.getBlockedReason());
        if (wasRunning) {
            dispatchNext();
        }
        return true;
    }

    /** 手动唤醒被人为阻塞的进程。等设备的进程必须等设备自然完成，不能手动唤醒。 */
    public boolean resumeProcess(int pid) {
        Pcb pcb = processes.find(pid);
        if (pcb == null || pcb.getState() != ProcessState.BLOCKED) {
            return false;
        }
        if (pcb.getDeviceType() != null) {
            return false;
        }
        processes.wake(pcb);
        log.process(clock, "手动唤醒 P" + pid + "，转入就绪队列");
        if (processes.getRunning() != null && processes.getRunning().isIdle()) {
            processes.preemptRunning();
            dispatchNext();
        }
        return true;
    }

    /** 重置系统：清空所有进程、内存、设备状态，系统时钟归零；磁盘文件保留。 */
    public void resetSystem() {
        for (Pcb pcb : processes.activeProcesses()) {
            cpu.saveTo(pcb);
            processes.destroy(pcb, clock);
        }
        processes.reset();
        memory.reset();
        devices.reset();
        cpu.reset();
        clock = 0;
        arrivalCountdown = 1;
        log.clear();
        log.system(0, "系统已重置：时钟归零、进程清空、内存与设备复位（磁盘文件保留）");
    }

    /** 格式化磁盘并重新安装默认文件树（会先结束所有进程并关闭打开的文件）。 */
    public void formatDisk(boolean installDefaultTree) {
        resetSystem();
        if (installDefaultTree) {
            fileSystem.format();
            fileSystem.installDefaultTree();
            log.file(0, "磁盘已格式化，并重新写入默认文件树");
        } else {
            fileSystem.format();
            log.file(0, "磁盘已格式化（空盘）");
        }
        programCache = null;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** \BIN 下所有可执行文件的路径（缓存到下次格式化）。 */
    public List<String> availablePrograms() {
        if (programCache == null) {
            programCache = new ArrayList<>();
            if (fileSystem.isDirectory("\\BIN")) {
                for (DirEntry entry : fileSystem.list("\\BIN")) {
                    if (!entry.isDirectory() && FileAttribute.isExecutable(entry.getAttribute())) {
                        programCache.add(PathUtil.join("\\BIN", entry.getFullName()));
                    }
                }
            }
            programCache.sort(Comparator.naturalOrder());
        }
        return new ArrayList<>(programCache);
    }

    /** 磁盘上的目录树（界面文件区用）。 */
    public cn.scau.os.fs.DirNode tree() {
        return fileSystem.buildTree();
    }

    public FileSystem getFileSystem() {
        return fileSystem;
    }

    public MemoryManager getMemory() {
        return memory;
    }

    public DeviceManager getDevices() {
        return devices;
    }

    public ProcessManager getProcesses() {
        return processes;
    }

    public Cpu getCpu() {
        return cpu;
    }

    public EventLog getLog() {
        return log;
    }

    public long getClock() {
        return clock;
    }

    public Path getWorkDirectory() {
        return workDirectory;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        log.system(clock, paused ? "系统已暂停（时钟停止推进）" : "系统继续运行");
    }

    public boolean isAutoArrival() {
        return autoArrival;
    }

    public void setAutoArrival(boolean autoArrival) {
        this.autoArrival = autoArrival;
        log.system(clock, autoArrival ? "已开启随机进程到达" : "已关闭随机进程到达");
    }

    public int getQuantum() {
        return processes.getQuantum();
    }

    public void setQuantum(int quantum) {
        if (quantum < 1) {
            throw new IllegalArgumentException("时间片必须大于 0");
        }
        processes.setQuantum(quantum);
        log.system(clock, "时间片设置为 " + quantum + " 个时间单位（下一次调度生效）");
    }

    public int getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(int tickIntervalMs) {
        if (tickIntervalMs < 20) {
            throw new IllegalArgumentException("模拟速度不能超过 20ms/单位");
        }
        this.tickIntervalMs = tickIntervalMs;
    }

    public int getArrivalCountdown() {
        return arrivalCountdown;
    }

    /** 手动录入一条文件操作日志（命令层成功后调用）。 */
    public void logFile(String message) {
        log.file(clock, message);
    }

    // ==================================================================
    // 快照
    // ==================================================================

    /** 生成一份完整的系统状态快照。界面只读它，不碰模型。 */
    public SystemSnapshot snapshot() {
        SystemSnapshot s = new SystemSnapshot();
        s.clock = clock;
        s.quantum = processes.getQuantum();
        s.paused = paused;
        s.autoArrival = autoArrival;
        s.nextArrival = arrivalCountdown;

        Pcb running = processes.getRunning();
        s.running = running == null ? null : SystemSnapshot.PcbView.of(running);
        for (Pcb pcb : processes.readyQueue()) {
            s.ready.add(SystemSnapshot.PcbView.of(pcb));
        }
        for (Pcb pcb : processes.blockedQueue()) {
            s.blocked.add(SystemSnapshot.PcbView.of(pcb));
        }
        for (Pcb pcb : processes.pendingQueue()) {
            s.pending.add(SystemSnapshot.PcbView.of(pcb));
        }
        for (Pcb pcb : processes.finishedQueue()) {
            s.finished.add(SystemSnapshot.PcbView.of(pcb));
        }

        List<Pcb> active = processes.activeProcesses();
        active.sort(Comparator.comparingInt(Pcb::getPid));
        for (Pcb pcb : active) {
            s.all.add(SystemSnapshot.PcbView.of(pcb));
        }

        s.partitions = memory.partitions();
        s.memoryUsed = memory.usedBytes();
        s.memoryTotal = MemoryManager.USER_MEMORY_SIZE;

        List<Device> allDevices = devices.devices();
        for (Device device : allDevices) {
            String ownerName = "-";
            if (device.isBusy()) {
                Pcb owner = processes.find(device.getOwnerPid());
                ownerName = owner == null ? "P" + device.getOwnerPid() : owner.displayName();
            }
            s.devices.add(SystemSnapshot.DeviceView.of(device, ownerName));
        }
        s.deviceTotal = allDevices.size();
        s.deviceBusy = devices.totalBusy();
        for (DeviceType type : DeviceType.values()) {
            List<String> waiters = new ArrayList<>();
            for (DeviceRequest request : devices.waitQueueOf(type)) {
                waiters.add("P" + request.pid() + "(" + request.duration() + ")");
            }
            s.waitQueues.put(type.getLabel(), waiters);
        }

        s.tree = fileSystem.buildTree();
        s.fat = fileSystem.fatSnapshot();
        s.blockOwners = fileSystem.blockOwners();
        s.freeBlocks = fileSystem.freeBlocks();
        s.usedBlocks = fileSystem.usedBlocks();
        s.dataBlocks = Fat.DATA_BLOCK_COUNT;
        s.openFileCount = fileSystem.openFileCount();
        for (OpenFile openFile : fileSystem.getOpenFiles().list()) {
            s.openFilePaths.add(openFile.getPath());
        }

        s.logs = log.entries();
        return s;
    }

    @Override
    public void close() {
        fileSystem.close();
    }
}
