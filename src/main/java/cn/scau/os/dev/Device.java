package cn.scau.os.dev;

/**
 * 一个具体的设备实例，例如 "A1"、"B3"。
 *
 * <p>独占设备的核心状态只有三个：谁在占用（{@link #ownerPid}）、还要用多久
 * （{@link #remaining}）、以及它属于哪一类。倒计时归零由
 * {@link DeviceManager#tickDevices()} 统一推进，避免多个定时器各扣一次。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一台具体设备，比如 {@code A1}。它记着"现在谁在用、还剩几拍"。</li>
 *   <li><b>从哪读起</b>：{@code id} / {@code type} / {@code ownerPid} / {@code remaining}。</li>
 *   <li><b>易错点</b>：{@code ownerPid == 0} 表示空闲，不是"被 0 号进程占用"
 *       （0 号是闲逛进程，永远不申请设备）。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public class Device {

    /** 空闲设备没有占用者。 */
    public static final int NO_OWNER = -1;

    private final DeviceType type;
    private final int indexInType;
    private final String id;
    private int ownerPid;
    private int remaining;

    public Device(DeviceType type, int indexInType) {
        this.type = type;
        this.indexInType = indexInType;
        this.id = type.getLabel() + (indexInType + 1);
        this.ownerPid = NO_OWNER;
        this.remaining = 0;
    }

    public DeviceType getType() {
        return type;
    }

    public int getIndexInType() {
        return indexInType;
    }

    /** 设备编号，如 A1。 */
    public String getId() {
        return id;
    }

    public int getOwnerPid() {
        return ownerPid;
    }

    public int getRemaining() {
        return remaining;
    }

    public boolean isBusy() {
        return ownerPid != NO_OWNER;
    }

    /** 把设备分配给某个进程，并开始倒计时。 */
    public void assign(int pid, int duration) {
        if (isBusy()) {
            throw new IllegalStateException("设备 " + id + " 已被 P" + ownerPid + " 占用，不能重复分配");
        }
        if (duration < 0) {
            throw new IllegalArgumentException("设备使用时长不能为负数");
        }
        this.ownerPid = pid;
        this.remaining = duration;
    }

    /** 释放设备。重复释放会被这里挡住，不会出现"负占用"。 */
    public void release() {
        if (!isBusy()) {
            throw new IllegalStateException("设备 " + id + " 本来就空闲，不能重复释放");
        }
        this.ownerPid = NO_OWNER;
        this.remaining = 0;
    }

    /** 倒计时减 1，返回是否刚好归零。 */
    boolean tick() {
        if (!isBusy()) {
            return false;
        }
        if (remaining > 0) {
            remaining--;
        }
        return remaining == 0;
    }

    @Override
    public String toString() {
        return id + (isBusy() ? "(P" + ownerPid + ", 剩 " + remaining + ")" : "(空闲)");
    }
}
