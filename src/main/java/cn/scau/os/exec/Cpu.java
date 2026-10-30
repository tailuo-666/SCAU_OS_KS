package cn.scau.os.exec;

import cn.scau.os.dev.DeviceType;
import cn.scau.os.mem.MemoryManager;
import cn.scau.os.proc.Pcb;

/**
 * 中央处理器的模拟。
 *
 * <p><b>在模拟操作系统中的角色</b>：课程要求用"无参数的 CPU() 函数"模拟单个 CPU，
 * 本类对应它的内核 {@link #executeStep(Pcb)}——每次调用执行<b>一条</b>指令。
 * 之所以不再写成"死循环"，是因为整个系统由统一逻辑时钟驱动，
 * 用真实线程 + sleep 模拟会让状态无法复现、也无法测试。
 *
 * <h2>寄存器</h2>
 * <ul>
 *   <li>{@code PC} 程序计数器：下一条要执行的指令序号（相对进程内存起始地址的偏移）</li>
 *   <li>{@code X}  数据寄存器 AX：程序里的变量 x，取值 0~255</li>
 *   <li>{@code IR} 指令寄存器：当前指令的机器码，仅用于显示</li>
 * </ul>
 *
 * <p>进程切换时，{@link #saveTo(Pcb)} 把 PC / X 存进 PCB，
 * {@link #restoreFrom(Pcb)} 再取回来，这就是"保存现场 / 恢复现场"。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一颗只会"取指—译码—执行"的假 CPU，一次执行一条指令。</li>
 *   <li><b>从哪读起</b>：{@link #executeStep} 是唯一入口，它从内存里按 PC 取一个字节，
 *       翻译成动作，然后改 X 或 PC。</li>
 *   <li><b>关键设计</b>：变量 X 和 PC 都存在 PCB 里，不在 CPU 里——
 *       这样"换进程"只要换 PCB 就等于换了现场，这就是上下文切换。</li>
 *   <li><b>易错点</b>：申请设备时 CPU 自己不阻塞，它只是把"我想申请"这个结果
 *       （{@code CpuOutcome}）报告给内核，由内核决定阻塞谁。</li>
 *   <li><b>分工</b>：成员 A 主讲；其他人最常问"X 会不会小于 0"（答：会饱和在 0）。</li>
 * </ul>
 */
public class Cpu {

    /** X 寄存器下界。 */
    public static final int X_MIN = 0;
    /** X 寄存器上界。 */
    public static final int X_MAX = 255;

    private int pc;
    private int x;
    private int ir;

    public int getPc() {
        return pc;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    /** 当前指令机器码（无符号）。 */
    public int getIr() {
        return ir;
    }

    /** 复位所有寄存器。 */
    public void reset() {
        pc = 0;
        x = 0;
        ir = 0;
    }

    /** 把 PCB 中保存的现场恢复到寄存器。 */
    public void restoreFrom(Pcb pcb) {
        this.pc = pcb.getPc();
        this.x = pcb.getX();
        this.ir = 0;
    }

    /** 把寄存器现场保存进 PCB。 */
    public void saveTo(Pcb pcb) {
        pcb.setPc(pc);
        pcb.setX(x);
    }

    /**
     * 执行一条指令。
     *
     * <p>指令从进程在内存中的副本里取（{@code 内存起始地址 + PC}），
     * 而不是从磁盘文件里取——这才是"装入主存后执行"。
     *
     * @param memory 主存（用户区）
     * @param pcb    当前运行进程
     * @return 执行结果，由引擎据此处理设备申请 / 进程结束等后续动作
     */
    public CpuOutcome executeStep(MemoryManager memory, Pcb pcb) {
        // 取指之前先确认 PC 落在程序范围内：越界不当"跳过"处理，而是判为非法。
        // 因为一个正常结束的程序一定以 end 收尾，跑到范围外说明这个程序文件本身有问题。
        if (pc < 0) {
            pc = 0;
        }
        if (pc >= pcb.getMemorySize()) {
            // 程序计数器越过程序末尾：按非正常结束处理，绝不悄悄"跳过"
            return CpuOutcome.invalid("<PC 越界>", x);
        }
        // ① 取指：从"内存里的程序副本"取一个字节。注意地址要加内存起址，
        //    因为程序被装载到了内存的某个偏移上，PC 是相对程序开头的。
        ir = memory.read(pcb.getMemoryStart() + pc);
        // ② 译码：一个字节 → 一条可读的指令（这一步全靠 InstructionCodec）
        InstructionCodec.Decoded decoded = InstructionCodec.decode(ir);
        String text = decoded.getText();
        pcb.setCurrentInstruction(text);   // 记下来，界面上"当前指令"一栏显示的就是它

        // ③ 执行：每一种指令对应一种动作，动作完就改 X 或 PC
        switch (decoded.getKind()) {
            case ASSIGN:
                x = decoded.getValue();
                pc++;
                return CpuOutcome.valueUpdated(text, x, "");

            case INCREMENT:
                if (x >= X_MAX) {
                    // 统一规则：饱和而不是回绕，并记录说明，避免静默溢出
                    x = X_MAX;
                    pc++;
                    return CpuOutcome.valueUpdated(text, x, "X 已到上界 255，保持 255");
                }
                x++;
                pc++;
                return CpuOutcome.valueUpdated(text, x, "");

            case DECREMENT:
                if (x <= X_MIN) {
                    x = X_MIN;
                    pc++;
                    return CpuOutcome.valueUpdated(text, x, "X 已到下界 0，保持 0");
                }
                x--;
                pc++;
                return CpuOutcome.valueUpdated(text, x, "");

            case DEVICE:
                pc++;   // 先推进 PC：进程恢复后从下一条指令继续
                return CpuOutcome.deviceRequest(text, DeviceType.fromChar(decoded.getDevice()),
                        decoded.getDuration(), x);

            case END:
                return CpuOutcome.end(text, x);

            case INVALID:
            default:
                return CpuOutcome.invalid(text, x);
        }
    }

    /** 当前寄存器的文字描述，用于日志。 */
    public String registers() {
        return "PC=" + pc + " X=" + x + " IR=" + ir;
    }
}
