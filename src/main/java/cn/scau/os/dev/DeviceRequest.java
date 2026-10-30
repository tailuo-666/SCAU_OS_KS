package cn.scau.os.dev;

/**
 * 一条设备申请请求（等待队列里的一项）。
 *
 * <p>必须把"时长"一起记住：进程在等待队列里排队时，指令已经执行过了，
 * 等它真正拿到设备时要按当初申请的时长开始倒计时。
 *
 * @param pid      申请进程号
 * @param duration 需要使用的逻辑时间单位数
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：等待队列里的一张"号票"：谁的、要用几拍。</li>
 *   <li><b>为什么用 record</b>：它天生不可变，队列里的号票不该被改写。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public record DeviceRequest(int pid, int duration) {

    @Override
    public String toString() {
        return "P" + pid + "(" + duration + ")";
    }
}
