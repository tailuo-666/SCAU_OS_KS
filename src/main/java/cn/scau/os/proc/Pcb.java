package cn.scau.os.proc;

import cn.scau.os.dev.DeviceType;

/**
 * 进程控制块 PCB。
 *
 * <p>PCB 是"进程存在的唯一标志"，也是进程被切换时保存现场的地方。
 * 本系统最多 10 个普通 PCB，另外有一个不占 PCB 的闲逛进程（pid = 0）。
 *
 * <p>字段分四组，对应课程要求的"进程标识 / 处理机状态 / 进程调度信息 / 资源占用"：
 * <ul>
 *   <li>标识：pid、程序文件路径</li>
 *   <li>处理机现场：pc（程序计数器）、x（AX 寄存器的值）</li>
 *   <li>调度信息：状态、提交时间、已运行时间、剩余时间片、阻塞原因</li>
 *   <li>资源：内存起始地址与大小、设备类型</li>
 * </ul>
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：进程的全部"身份档案"：PID、状态、PC、X、内存起址、时间片余量、
 *       申请的设备……进程被换下 CPU 时，这些字段就是它的"存档"。</li>
 *   <li><b>从哪读起</b>：先看字段分组（身份 / 现场 / 资源 / 统计），
 *       再看 {@code saveContext} 与 {@code restoreContext}。</li>
 *   <li><b>核心思想</b>：CPU 上只有一个进程在跑，其余进程的"活着"全靠 PCB 里的这几个数字。</li>
 *   <li><b>易错点</b>：{@code pid == 0} 是闲逛进程，它不占内存、不占设备、
 *       也不会进就绪队列，只是让 CPU "永远不空转"。</li>
 *   <li><b>分工</b>：成员 A 主讲；成员 C 会问"界面上进程表每一列对应哪个字段"。</li>
 * </ul>
 */
public class Pcb {

    /** 闲逛进程的 PID。 */
    public static final int IDLE_PID = 0;

    private final int pid;
    private String programPath;
    private ProcessState state;

    // ---- 处理机现场 ----
    private int pc;
    private int x;

    // ---- 资源 ----
    private int memoryStart;
    private int memorySize;
    private DeviceType deviceType;      // 当前等待/占用的设备类型，没有则为 null

    // ---- 调度信息 ----
    private long submitTime;
    private long runTime;
    private int sliceRemaining;
    private String blockedReason;
    private long finishTime;

    // ---- 展示信息 ----
    private String currentInstruction = "";
    private String lastResult = "";
    private final boolean idle;
    private boolean waitingForMemory;

    public Pcb(int pid, String programPath, boolean idle) {
        this.pid = pid;
        this.programPath = programPath;
        this.idle = idle;
        this.state = ProcessState.NEW;
    }

    // ------------------------------------------------------------------
    // 基本访问器
    // ------------------------------------------------------------------

    public int getPid() {
        return pid;
    }

    public String getProgramPath() {
        return programPath;
    }

    public void setProgramPath(String programPath) {
        this.programPath = programPath;
    }

    public ProcessState getState() {
        return state;
    }

    public void setState(ProcessState state) {
        this.state = state;
    }

    public int getPc() {
        return pc;
    }

    public void setPc(int pc) {
        this.pc = pc;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getMemoryStart() {
        return memoryStart;
    }

    public void setMemoryStart(int memoryStart) {
        this.memoryStart = memoryStart;
    }

    public int getMemorySize() {
        return memorySize;
    }

    public void setMemorySize(int memorySize) {
        this.memorySize = memorySize;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(DeviceType deviceType) {
        this.deviceType = deviceType;
    }

    public long getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(long submitTime) {
        this.submitTime = submitTime;
    }

    public long getRunTime() {
        return runTime;
    }

    public void setRunTime(long runTime) {
        this.runTime = runTime;
    }

    public void addRunTime() {
        this.runTime++;
    }

    public int getSliceRemaining() {
        return sliceRemaining;
    }

    public void setSliceRemaining(int sliceRemaining) {
        this.sliceRemaining = sliceRemaining;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public long getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(long finishTime) {
        this.finishTime = finishTime;
    }

    public String getCurrentInstruction() {
        return currentInstruction;
    }

    public void setCurrentInstruction(String currentInstruction) {
        this.currentInstruction = currentInstruction;
    }

    public String getLastResult() {
        return lastResult;
    }

    public void setLastResult(String lastResult) {
        this.lastResult = lastResult;
    }

    public boolean isIdle() {
        return idle;
    }

    public boolean isWaitingForMemory() {
        return waitingForMemory;
    }

    public void setWaitingForMemory(boolean waitingForMemory) {
        this.waitingForMemory = waitingForMemory;
    }

    /** 显示用名称：P1、P2……闲逛进程显示为 "IDLE"。 */
    public String displayName() {
        return idle ? "IDLE" : "P" + pid;
    }

    @Override
    public String toString() {
        return displayName() + "[" + state.getLabel() + " pc=" + pc + " x=" + x + "]";
    }
}
