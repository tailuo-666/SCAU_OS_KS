package cn.scau.os.exec;

import cn.scau.os.dev.DeviceType;

/**
 * 一条指令执行后的结果，供操作系统引擎决定下一步做什么。
 *
 * <p>CPU 只负责"解释并执行"这一条指令本身的计算部分；涉及到设备、进程撤销
 * 这类需要协调其他模块的动作，以本对象的形式交还给引擎处理，
 * 这样 CPU 不必认识进程队列和设备表，也能被单独测试。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一条指令执行完的"回执单"——我是正常继续、我结束了、还是我想申请设备。</li>
 *   <li><b>从哪读起</b>：三个静态工厂方法 {@code normal()} / {@code end()} /
 *       {@code device(...)}，一眼就能看懂全部可能。</li>
 *   <li><b>为什么不用 boolean</b>：一条指令的结果有三种去向，boolean 装不下。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public class CpuOutcome {

    /** 执行结果类型。 */
    public enum Kind {
        /** 赋值 / 自增 / 自减：执行完成，下一条指令继续。 */
        VALUE_UPDATED,
        /** 设备申请指令：需要引擎去申请设备并把进程阻塞。 */
        DEVICE_REQUEST,
        /** end 指令：需要引擎撤销进程。 */
        END,
        /** 非法指令编码：按异常结束处理。 */
        INVALID
    }

    private final Kind kind;
    private final String instruction;
    private final DeviceType deviceType;
    private final int duration;
    private final int xValue;
    private final String note;

    private CpuOutcome(Kind kind, String instruction, DeviceType deviceType,
                       int duration, int xValue, String note) {
        this.kind = kind;
        this.instruction = instruction;
        this.deviceType = deviceType;
        this.duration = duration;
        this.xValue = xValue;
        this.note = note;
    }

    public static CpuOutcome valueUpdated(String instruction, int xValue, String note) {
        return new CpuOutcome(Kind.VALUE_UPDATED, instruction, null, 0, xValue, note);
    }

    public static CpuOutcome deviceRequest(String instruction, DeviceType type, int duration, int xValue) {
        return new CpuOutcome(Kind.DEVICE_REQUEST, instruction, type, duration, xValue, "");
    }

    public static CpuOutcome end(String instruction, int xValue) {
        return new CpuOutcome(Kind.END, instruction, null, 0, xValue, "");
    }

    public static CpuOutcome invalid(String instruction, int xValue) {
        return new CpuOutcome(Kind.INVALID, instruction, null, 0, xValue, "非法指令");
    }

    public Kind getKind() {
        return kind;
    }

    /** 指令的可读形式，如 {@code x=10}。 */
    public String getInstruction() {
        return instruction;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public int getDuration() {
        return duration;
    }

    /** 执行后 X 寄存器的值。 */
    public int getXValue() {
        return xValue;
    }

    /** 附加说明（例如 X 溢出被饱和）。 */
    public String getNote() {
        return note;
    }

    @Override
    public String toString() {
        return instruction + " → X=" + xValue + (note.isEmpty() ? "" : "（" + note + "）");
    }
}
