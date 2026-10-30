package cn.scau.os.engine;

/**
 * 一条系统事件日志。
 *
 * @param time    逻辑时间戳（系统时钟的第几个时间单位）
 * @param level   事件级别，用于界面着色
 * @param message 中文描述
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一条日志 = 逻辑时间 + 级别 + 正文，用 record 写成一行。</li>
 *   <li><b>为什么时间用逻辑时钟</b>：这样"第 6 拍发生了什么"在任何机器上都一样，
 *       截图和文档能对得上。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public record LogEntry(long time, LogLevel level, String message) {

    /** 带时间戳的单行文本，例如 {@code [0012] 进程  P3 申请 A 类设备成功}。 */
    public String format() {
        return String.format("[%04d] %-4s %s", time, level.getLabel(), message);
    }

    @Override
    public String toString() {
        return format();
    }
}
