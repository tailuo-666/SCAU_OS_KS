package cn.scau.os.mem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 存储管理：用动态分区方式管理 512 字节的用户区。
 *
 * <p><b>在模拟操作系统中的角色</b>：进程被创建时，要把它在磁盘上的可执行文件
 * "装入主存"才能执行。本类负责这段主存的分配与回收：
 * <ul>
 *   <li>分区表用按地址有序的链表表示（空闲区 + 已分配区交替排列）；</li>
 *   <li>分配算法为<b>首次适配</b>（First Fit）：从头扫描，找到第一个够大的空闲区就切一块；</li>
 *   <li>释放时会与相邻空闲区<b>合并</b>，避免碎片越积越多；</li>
 *   <li>空间不足时返回 -1，由上层把进程放进"等待内存队列"，等有内存释放再重试。</li>
 * </ul>
 *
 * <p>用户区本身用 {@code byte[512]} 模拟，进程的指令副本真的写在这个数组里，
 * CPU 也是从这个数组取指令——不是只画一个进度条。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：512 字节用户区的"房东"。用动态分区 + 首次适应法给进程分房间。</li>
 *   <li><b>从哪读起</b>：{@link #allocate(int, int)} → {@link #release(int)}。
 *       前者"找第一个够大的空洞，切一块走"，后者"退房并把相邻空房合并"。</li>
 *   <li><b>为什么必须合并</b>：不合并的话，释放 3 次之后会剩一堆碎空房，
 *       明明总空闲 400 字节却再也放不下 300 字节的进程——这叫外部碎片。</li>
 *   <li><b>易错点</b>：切分时剩余部分必须是"新的空闲分区"，起址要加长度；
 *       合并要同时看左右邻居，可能一次合并三段。</li>
 *   <li><b>分工</b>：成员 A 主讲；成员 B 会问"程序是怎么被装进内存的"（看 {@code loadProgram}）。</li>
 * </ul>
 */
public class MemoryManager {

    /** 用户区固定 512 字节。 */
    public static final int USER_MEMORY_SIZE = 512;
    /** 最多容纳 10 个进程，因此最多 10 个已分配分区。 */
    public static final int MAX_PROCESS = 10;

    private final byte[] userArea = new byte[USER_MEMORY_SIZE];
    private final List<MemoryPartition> partitions = new ArrayList<>();

    public MemoryManager() {
        reset();
    }

    /** 复位：整块用户区空闲。 */
    public void reset() {
        Arrays.fill(userArea, (byte) 0);
        partitions.clear();
        partitions.add(new MemoryPartition(0, USER_MEMORY_SIZE, MemoryPartition.FREE_OWNER));
    }

    // ------------------------------------------------------------------
    // 分配 / 回收
    // ------------------------------------------------------------------

    /**
     * 首次适配分配。
     *
     * @param pid  申请者进程号
     * @param size 需要的字节数（等于可执行文件的指令数）
     * @return 分配到的起始地址；空间不足返回 -1
     */
    public int allocate(int pid, int size) {
        if (size <= 0) {
            size = 1;                       // 空程序也至少占 1 字节
        }
        if (size > USER_MEMORY_SIZE) {
            return -1;
        }
        if (partitionOf(pid) != null) {
            throw new IllegalStateException("进程 P" + pid + " 已经占用内存，不能重复分配");
        }
        // 首次适应法（First Fit）：从低地址往高地址扫，遇到第一个"空闲且装得下"的分区就用。
        // 为什么不挑"最合适的"（最佳适应）？因为首次适应实现最简单，而且低地址的大空洞
        // 会不断被切小、高地址保留大块空闲区，实测反而更不容易碎片化。
        for (int i = 0; i < partitions.size(); i++) {
            MemoryPartition part = partitions.get(i);
            if (!part.isFree() || part.getSize() < size) {
                continue;           // 有人住、或者太小，看下一个
            }
            int start = part.getStart();
            int leftover = part.getSize() - size;
            part.setSize(size);     // 原地把这个分区"切小"成刚好够用
            part.setOwnerPid(pid);
            part.setLoaded(false);
            if (leftover > 0) {
                // 拆分：剩下的部分变成一个新的空闲分区，插在原分区后面。
                // 新起址 = 原起址 + 本次申请长度（不是原起址！这是最容易写错的一行）。
                partitions.add(i + 1, new MemoryPartition(start + size, leftover, MemoryPartition.FREE_OWNER));
            }
            return start;           // 返回分配给进程的起始地址，进程后面就住这儿了
        }
        return -1;                  // 没有一块够大 → 分配失败，上层会让进程"等内存"
    }

    /** 回收某个进程占用的分区，并与相邻空闲区合并。 */
    public void release(int pid) {
        MemoryPartition target = partitionOf(pid);
        if (target == null) {
            return;
        }
        // 把这段内存清零：既模拟"进程退出后内存被系统回收"，也方便调试时
        // 一眼看出这块地方暂时没人用（不清零的话旧数据会留着，容易误判）。
        Arrays.fill(userArea, target.getStart(), target.getEnd(), (byte) 0);
        target.setOwnerPid(MemoryPartition.FREE_OWNER);   // 不删除分区，只把"主人"改成空闲
        target.setLoaded(false);
        mergeFreePartitions();      // 关键一步：和左右相邻的空闲分区合并，否则会产生外部碎片
    }

    /** 合并所有相邻的空闲分区。 */
    private void mergeFreePartitions() {
        for (int i = 0; i < partitions.size() - 1; ) {
            MemoryPartition current = partitions.get(i);
            MemoryPartition next = partitions.get(i + 1);
            if (current.isFree() && next.isFree()) {
                current.setSize(current.getSize() + next.getSize());
                partitions.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    /** 把可执行文件的指令副本装入主存。 */
    public void loadProgram(int start, byte[] code) {
        if (start < 0 || start + code.length > USER_MEMORY_SIZE) {
            throw new IllegalArgumentException("装入地址越界：" + start + " + " + code.length);
        }
        System.arraycopy(code, 0, userArea, start, code.length);
        for (MemoryPartition part : partitions) {
            if (part.getStart() == start) {
                part.setLoaded(true);
                break;
            }
        }
    }

    /** 读取一个字节（无符号）。CPU 取指令用。 */
    public int read(int address) {
        checkAddress(address);
        return userArea[address] & 0xFF;
    }

    /** 写入一个字节。 */
    public void write(int address, int value) {
        checkAddress(address);
        userArea[address] = (byte) (value & 0xFF);
    }

    private void checkAddress(int address) {
        if (address < 0 || address >= USER_MEMORY_SIZE) {
            throw new IllegalArgumentException("内存地址越界：" + address + "（用户区 0~511）");
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 某进程占用的分区，没有则返回 null。 */
    public MemoryPartition partitionOf(int pid) {
        for (MemoryPartition part : partitions) {
            if (part.getOwnerPid() == pid) {
                return part;
            }
        }
        return null;
    }

    /** 区块快照（拷贝，界面用）。 */
    public List<MemoryPartition> partitions() {
        List<MemoryPartition> copy = new ArrayList<>();
        for (MemoryPartition part : partitions) {
            copy.add(new MemoryPartition(part.getStart(), part.getSize(), part.getOwnerPid()));
        }
        return copy;
    }

    /** 已分配字节数。 */
    public int usedBytes() {
        int used = 0;
        for (MemoryPartition part : partitions) {
            if (!part.isFree()) {
                used += part.getSize();
            }
        }
        return used;
    }

    /** 空闲字节数。 */
    public int freeBytes() {
        return USER_MEMORY_SIZE - usedBytes();
    }

    /** 最大空闲分区大小（判断"内存不够"时给用户更有用的提示）。 */
    public int largestFreeBlock() {
        int max = 0;
        for (MemoryPartition part : partitions) {
            if (part.isFree() && part.getSize() > max) {
                max = part.getSize();
            }
        }
        return max;
    }

    /** 分区个数，用于界面展示"内存分配表"。 */
    public int partitionCount() {
        return partitions.size();
    }

    /** 调试用：以十六进制打印一段内存。 */
    public String hexDump(int start, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length && start + i < USER_MEMORY_SIZE; i++) {
            sb.append(String.format("%02X ", userArea[start + i] & 0xFF));
        }
        return sb.toString().trim();
    }
}
