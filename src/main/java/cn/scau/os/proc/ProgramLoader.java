package cn.scau.os.proc;

/**
 * 从磁盘读取可执行文件内容的接口。
 *
 * <p>进程管理需要"程序文件的指令字节"才能装入内存，但它不应该直接依赖文件系统类，
 * 否则两个模块就绑死了。由 {@code OperatingSystem} 在构造时把文件系统的读取能力
 * 以这个接口传进来，进程管理模块因此可以独立测试。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一个只有一个方法的接口——"给我路径，我给你程序的机器码"。</li>
 *   <li><b>为什么要接口</b>：进程管理不该依赖文件系统（否则单元测试必须造一台磁盘）。
 *       测试里传一个 lambda 就能假装读到了程序。</li>
 *   <li><b>分工</b>：成员 A 主讲（这是"依赖倒置"最简单的一个例子）。</li>
 * </ul>
 */
@FunctionalInterface
public interface ProgramLoader {

    /**
     * 读取可执行文件的全部指令字节。
     *
     * @param path 绝对路径，例如 {@code \BIN\P01.E}
     * @return 指令字节数组（每个字节一条指令）
     */
    byte[] load(String path);
}
