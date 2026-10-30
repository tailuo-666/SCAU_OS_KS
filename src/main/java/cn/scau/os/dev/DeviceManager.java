package cn.scau.os.dev;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 设备管理：设备分配表 + 三类设备各自的等待队列。
 *
 * <p><b>在模拟操作系统中的角色</b>：当进程执行到 {@code !A3} 这类指令时，
 * 会调用本类申请设备。规则如下：
 * <ol>
 *   <li>该类设备还有空闲实例 → 立刻分配，倒计时从"使用时长"开始；</li>
 *   <li>没有空闲实例 → 进程进入该类设备的等待队列，保持阻塞；</li>
 *   <li>设备倒计时归零 → 释放设备并通知上层（I/O 中断），上层唤醒原进程；
 *       同时若等待队列非空，把设备直接交给队首进程并开始它自己的倒计时。</li>
 * </ol>
 *
 * <p>本类只维护"设备-进程"的对应关系，不负责修改进程状态（那是进程管理模块的事），
 * 但所有会破坏一致性的操作（重复分配、重复释放）都在这里被拦住。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：8 台设备的"调度员"：谁能用、谁在排队、用完了叫醒谁。</li>
 *   <li><b>从哪读起</b>：{@link #allocate(int, DeviceType, int)}（有就占、没有就进等待队列）
 *       → {@link #tickDevices()}（每拍倒计时减一，归零就释放）
 *       → {@code handDeviceToNextWaiter}（把设备直接交给队首，不还给系统）。</li>
 *   <li><b>关键设计</b>：归零时"设备直接交给下一个等待者"，避免出现
 *       "刚释放就被第三个进程抢走、排队的第一个反而饿死"的不公平。</li>
 *   <li><b>易错点</b>：进程被杀掉时要先把它从等待队列里删掉（{@code removeWaiter}），
 *       否则会出现"幽灵排队者"。</li>
 *   <li><b>分工</b>：成员 A 主讲；成员 D 会问"界面上的等待队列从哪来"。</li>
 * </ul>
 */
public class DeviceManager {

    private final List<Device> devices = new ArrayList<>();
    private final Map<DeviceType, Deque<DeviceRequest>> waitQueues = new EnumMap<>(DeviceType.class);

    public DeviceManager() {
        for (DeviceType type : DeviceType.values()) {
            for (int i = 0; i < type.getCount(); i++) {
                devices.add(new Device(type, i));
            }
            waitQueues.put(type, new ArrayDeque<>());
        }
    }

    // ------------------------------------------------------------------
    // 申请 / 分配
    // ------------------------------------------------------------------

    /**
     * 尝试立即分配一个空闲设备。
     *
     * @return 分配成功返回 true；没有空闲实例返回 false（调用方应把进程加入等待队列）
     */
    public boolean request(DeviceType type, int pid, int duration) {
        Device device = findFreeDevice(type);
        if (device == null) {
            return false;
        }
        device.assign(pid, duration);
        return true;
    }

    /** 把进程加入某类设备的等待队列。 */
    public void enqueue(DeviceType type, int pid, int duration) {
        waitQueues.get(type).addLast(new DeviceRequest(pid, duration));
    }

    /** 取出等待队列队首；队列空返回 null。 */
    public DeviceRequest pollWait(DeviceType type) {
        return waitQueues.get(type).pollFirst();
    }

    /** 查看队首但不移除。 */
    public DeviceRequest peekWait(DeviceType type) {
        return waitQueues.get(type).peekFirst();
    }

    /**
     * 把设备直接交给队首等待者，并按它当初申请的时长开始倒计时。
     *
     * @return 被分配的请求；队列为空返回 null
     */
    public DeviceRequest dispatchToNext(Device device) {
        DeviceRequest request = pollWait(device.getType());
        if (request == null) {
            return null;
        }
        if (device.isBusy()) {
            throw new IllegalStateException("设备 " + device.getId() + " 尚未释放，不能直接分配");
        }
        device.assign(request.pid(), request.duration());
        return request;
    }

    // ------------------------------------------------------------------
    // 倒计时与释放
    // ------------------------------------------------------------------

    /**
     * 所有占用中的设备倒计时减 1，返回本次刚好归零的设备（尚未释放）。
     * 上层负责：释放设备 → 唤醒原进程 → 把设备交给队首等待者。
     */
    public List<Device> tickDevices() {
        // 每台"正在被占用"的设备倒计时减 1。减到 0 说明这次 I/O 做完了，
        // 把它收集起来返回给内核——注意这里只负责"报告完成"，
        // "叫醒哪个进程、设备接下来给谁"是内核的事（硬件只报告，软件决定后果）。
        List<Device> finished = new ArrayList<>();
        for (Device device : devices) {
            if (device.isBusy() && device.tick()) {
                finished.add(device);
            }
        }
        return finished;
    }

    /** 释放设备。 */
    public void release(Device device) {
        device.release();
    }

    /** 释放某进程占用的全部设备（进程被撤销时调用）。 */
    public List<Device> releaseAll(int pid) {
        List<Device> released = new ArrayList<>();
        for (Device device : devices) {
            if (device.getOwnerPid() == pid) {
                device.release();
                released.add(device);
            }
        }
        return released;
    }

    /** 从所有等待队列里摘掉某个进程（进程被撤销时调用，避免队列里留下"幽灵"）。 */
    public boolean removeWaiter(int pid) {
        boolean removed = false;
        for (Deque<DeviceRequest> queue : waitQueues.values()) {
            removed |= queue.removeIf(request -> request.pid() == pid);
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    private Device findFreeDevice(DeviceType type) {
        for (Device device : devices) {
            if (device.getType() == type && !device.isBusy()) {
                return device;
            }
        }
        return null;
    }

    /** 某进程正在使用的设备，没有则返回 null。 */
    public Device deviceOf(int pid) {
        for (Device device : devices) {
            if (device.getOwnerPid() == pid) {
                return device;
            }
        }
        return null;
    }

    public List<Device> devices() {
        return new ArrayList<>(devices);
    }

    public List<Device> devicesOf(DeviceType type) {
        List<Device> list = new ArrayList<>();
        for (Device device : devices) {
            if (device.getType() == type) {
                list.add(device);
            }
        }
        return list;
    }

    public List<DeviceRequest> waitQueueOf(DeviceType type) {
        return new ArrayList<>(waitQueues.get(type));
    }

    public int busyCount(DeviceType type) {
        int busy = 0;
        for (Device device : devices) {
            if (device.getType() == type && device.isBusy()) {
                busy++;
            }
        }
        return busy;
    }

    public int freeCount(DeviceType type) {
        return type.getCount() - busyCount(type);
    }

    /** 是否还有空闲设备（不限类型）。 */
    public boolean hasFreeDevice(DeviceType type) {
        return findFreeDevice(type) != null;
    }

    /** 总占用数，界面顶部状态条用。 */
    public int totalBusy() {
        int busy = 0;
        for (Device device : devices) {
            if (device.isBusy()) {
                busy++;
            }
        }
        return busy;
    }

    /** 重置：所有设备空闲、所有等待队列清空。 */
    public void reset() {
        for (Device device : devices) {
            if (device.isBusy()) {
                device.release();
            }
        }
        for (Deque<DeviceRequest> queue : waitQueues.values()) {
            queue.clear();
        }
    }
}
