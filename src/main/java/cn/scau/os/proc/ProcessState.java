package cn.scau.os.proc;

/**
 * 进程状态。任何一个进程在任一时刻只能处于其中一种状态，
 * 队列与状态必须严格一致（就绪队列里的进程一定是 READY，以此类推）。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：进程的五种状态。任何一个进程在任一时刻只能处于其中一种。</li>
 *   <li><b>状态迁移</b>：新建 → 就绪 → 运行 → （阻塞 | 就绪 | 完成），
 *       阻塞 → 就绪 只能由"事件发生"触发（设备用完、内存够了）。</li>
 *   <li><b>易错点</b>：课程里有的教材还分"创建态/终止态"，本项目把创建并入就绪、
 *       终止用 {@code DONE} 表示。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public enum ProcessState {

    /** 已建立 PCB，但还没拿到内存（等内存队列里的进程）。 */
    NEW("新建"),
    /** 在主存中，等待 CPU。 */
    READY("就绪"),
    /** 正在占用 CPU。 */
    RUNNING("运行"),
    /** 等待设备（或被人为阻塞）。 */
    BLOCKED("阻塞"),
    /** 已结束，PCB 已回收（保留一份记录用于显示）。 */
    DONE("完成");

    private final String label;

    ProcessState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
