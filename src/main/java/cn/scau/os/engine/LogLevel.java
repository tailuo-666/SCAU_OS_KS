package cn.scau.os.engine;

/**
 * 日志级别，仅用于界面着色，不影响业务逻辑。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：日志级别枚举（系统/进程/内存/设备/文件/错误）。</li>
 *   <li><b>注意</b>：这里的级别只影响界面颜色，不参与任何业务判断——
 *       不要把逻辑写在"日志级别"上。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public enum LogLevel {

    /** 系统级事件：启动、重置、时钟。 */
    SYSTEM("系统"),
    /** 进程事件：创建、调度、阻塞、唤醒、结束。 */
    PROCESS("进程"),
    /** 内存事件。 */
    MEMORY("内存"),
    /** 设备事件。 */
    DEVICE("设备"),
    /** 文件事件。 */
    FILE("文件"),
    /** 错误与中断。 */
    ERROR("异常");

    private final String label;

    LogLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
