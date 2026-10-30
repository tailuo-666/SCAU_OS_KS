package cn.scau.os.mem;

/**
 * 内存分区表的一个表项：一段连续的 512 字节用户区。
 *
 * <p>分区表是一个链表（本项目用按地址有序的 {@link java.util.List} 表示），
 * 表项记录了起始位置、长度和归属进程。{@code pid = FREE_OWNER} 表示空闲分区。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：分区表的一行——起址、长度、属于哪个进程（PID=0 表示空闲）。</li>
 *   <li><b>从哪读起</b>：{@code start} / {@code size} / {@code pid} 三个字段 +
 *       {@code isFree()}；{@code end()} 是"起址 + 长度"，边界判断全靠它。</li>
 *   <li><b>易错点</b>：分区是"左闭右开"的，[start, start+size)。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public class MemoryPartition {

    /** 空闲分区的归属标记。 */
    public static final int FREE_OWNER = -1;

    private final int start;
    private int size;
    private int ownerPid;
    /** 该分区是否存放了进程的指令副本（界面用不同颜色区分"已装入程序"）。 */
    private boolean loaded;

    public MemoryPartition(int start, int size, int ownerPid) {
        this.start = start;
        this.size = size;
        this.ownerPid = ownerPid;
        this.loaded = false;
    }

    public int getStart() {
        return start;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int getEnd() {
        return start + size;      // 左闭右开
    }

    public int getOwnerPid() {
        return ownerPid;
    }

    public void setOwnerPid(int ownerPid) {
        this.ownerPid = ownerPid;
    }

    public boolean isFree() {
        return ownerPid == FREE_OWNER;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    @Override
    public String toString() {
        return "[" + start + ", " + (getEnd() - 1) + "] " + size + "B "
                + (isFree() ? "空闲" : "P" + ownerPid);
    }
}
