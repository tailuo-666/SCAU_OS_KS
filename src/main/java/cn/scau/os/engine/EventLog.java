package cn.scau.os.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 系统事件日志（环形缓冲，只保留最近若干条）。
 *
 * <p>每条日志都带逻辑时间戳，因此日志顺序就是系统真实发生顺序，
 * 界面上看到的"创建进程 → 分配内存 → 申请设备 → 阻塞 → 唤醒"
 * 与模型内部的状态变化是同一条时间线。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一个最多存 500 条的环形日志。满了就丢最旧的，防止跑一晚上把内存吃光。</li>
 *   <li><b>从哪读起</b>：{@code add(...)} 与 {@code snapshot()}（复制一份给界面）。</li>
 *   <li><b>易错点</b>：{@code snapshot()} 必须返回副本，不能把内部 List 直接给界面。</li>
 *   <li><b>分工</b>：成员 A 主讲；成员 C 会问"日志颜色是怎么分的"。</li>
 * </ul>
 */
public class EventLog {

    /** 最多保留的日志条数。 */
    public static final int CAPACITY = 500;

    private final Deque<LogEntry> entries = new ArrayDeque<>();

    public void add(long time, LogLevel level, String message) {
        entries.addLast(new LogEntry(time, level, message));
        while (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
    }

    public void system(long time, String message) {
        add(time, LogLevel.SYSTEM, message);
    }

    public void process(long time, String message) {
        add(time, LogLevel.PROCESS, message);
    }

    public void memory(long time, String message) {
        add(time, LogLevel.MEMORY, message);
    }

    public void device(long time, String message) {
        add(time, LogLevel.DEVICE, message);
    }

    public void file(long time, String message) {
        add(time, LogLevel.FILE, message);
    }

    public void error(long time, String message) {
        add(time, LogLevel.ERROR, message);
    }

    /** 全部日志（按时间顺序）。 */
    public List<LogEntry> entries() {
        return new ArrayList<>(entries);
    }

    /** 最近 n 条。 */
    public List<LogEntry> recent(int n) {
        List<LogEntry> all = entries();
        int from = Math.max(0, all.size() - n);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
