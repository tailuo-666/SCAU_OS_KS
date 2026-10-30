package cn.scau.os.engine;

import cn.scau.os.dev.Device;
import cn.scau.os.fs.DirNode;
import cn.scau.os.mem.MemoryPartition;
import cn.scau.os.proc.Pcb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统状态快照：界面唯一的数据来源。
 *
 * <p><b>为什么要有快照</b>：如果界面各个面板各自从模型里取数据，很容易出现
 * "进程表显示 3 个进程、内存图显示 4 个分区"这类不一致。这里在每次时钟推进后
 * 一次性把模型状态整体复制出来，所有面板都渲染同一份快照，
 * 界面只读快照，永远不会修改模型，从而保证"界面刷新不改变业务状态"。
 *
 * <p>本类字段在构造时一次填充，之后当只读对象使用。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：某一时刻整个系统的"照片"。界面只读它，不读内核对象。</li>
 *   <li><b>为什么要有它</b>：如果界面直接持有内核里的 {@code List<Pcb>}，
 *       用户边看界面边跑时钟，就会读到"改了一半"的数据，甚至抛并发异常。
 *       快照把"读"和"算"彻底隔开，这也是本项目能不开线程就做到不卡界面的原因。</li>
 *   <li><b>从哪读起</b>：{@code clock} / {@code running} / {@code ready} / {@code partitions} /
 *       {@code devices} / {@code root} 这些字段，就是界面上 8 个区域各自的数据源。</li>
 *   <li><b>对前端意味着什么</b>：内部类 {@code PcbView}、{@code DeviceView}
 *       以及 {@code DirNode}、{@code MemoryPartition} 就是前后端之间的"数据契约"：
 *       界面要新增一列，先问这里有没有这个字段，而不是去翻 PCB。</li>
 *   <li><b>分工</b>：成员 A 定义并讲解契约；成员 C、D 按契约取数。</li>
 * </ul>
 */
public class SystemSnapshot {

    // ---- 时钟与控制状态 ----
    public long clock;
    public int quantum;
    public boolean paused;
    public boolean autoArrival;
    public int nextArrival;

    // ---- 进程 ----
    public PcbView running;
    public List<PcbView> ready = new ArrayList<>();
    public List<PcbView> blocked = new ArrayList<>();
    public List<PcbView> pending = new ArrayList<>();
    public List<PcbView> finished = new ArrayList<>();
    /** 运行 + 就绪 + 阻塞 + 等内存，按进程号排序，供主表显示。 */
    public List<PcbView> all = new ArrayList<>();

    // ---- 内存 ----
    public List<MemoryPartition> partitions = new ArrayList<>();
    public int memoryUsed;
    public int memoryTotal;

    // ---- 设备 ----
    public List<DeviceView> devices = new ArrayList<>();
    /** 设备类型 → 等待队列（形如 "P3(5)"）。 */
    public Map<String, List<String>> waitQueues = new LinkedHashMap<>();
    public int deviceBusy;
    public int deviceTotal;

    // ---- 文件系统 ----
    public DirNode tree;
    public int[] fat = new int[0];
    public String[] blockOwners = new String[0];
    public int freeBlocks;
    public int usedBlocks;
    public int dataBlocks;
    public int openFileCount;
    public List<String> openFilePaths = new ArrayList<>();

    // ---- 日志 ----
    public List<LogEntry> logs = new ArrayList<>();

    /** 进程表的行数据。 */
    public record PcbView(int pid, String name, String state, String program, int pc, int x,
                          int memoryStart, int memorySize, int slice, String reason,
                          String instruction, String lastResult, long submitTime, long runTime,
                          boolean idle) {

        public static PcbView of(Pcb pcb) {
            return new PcbView(pcb.getPid(), pcb.displayName(), pcb.getState().getLabel(),
                    pcb.getProgramPath(), pcb.getPc(), pcb.getX(),
                    pcb.getMemoryStart(), pcb.getMemorySize(), pcb.getSliceRemaining(),
                    pcb.getBlockedReason() == null ? "" : pcb.getBlockedReason(),
                    pcb.getCurrentInstruction(), pcb.getLastResult(),
                    pcb.getSubmitTime(), pcb.getRunTime(), pcb.isIdle());
        }

        /** 内存占用的简短显示。 */
        public String memoryText() {
            if (idle || memorySize == 0) {
                return "-";
            }
            return memorySize + "B @" + memoryStart;
        }
    }

    /** 设备表的行数据。 */
    public record DeviceView(String id, String type, boolean busy, String ownerName, int remaining) {

        public static DeviceView of(Device device, String ownerName) {
            return new DeviceView(device.getId(), device.getType().getLabel(),
                    device.isBusy(), ownerName, device.getRemaining());
        }

        public String stateText() {
            return busy ? "占用中" : "空闲";
        }

        public String ownerText() {
            return busy ? ownerName : "-";
        }

        public String remainingText() {
            return busy ? String.valueOf(remaining) : "-";
        }
    }
}
