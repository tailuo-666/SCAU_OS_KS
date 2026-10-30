package cn.scau.os.fs;

/**
 * 文件系统统一异常。
 * <p>
 * 文件系统的所有"可预期的业务错误"（路径不存在、目录非空、磁盘空间不足……）
 * 都以本异常抛出，由命令层 {@code Shell} 捕获后翻译成一条中文提示，
 * 这样就不用在每个调用点重复写 try/catch，也不会把错误悄悄吞掉。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：文件系统专用异常。所有"磁盘满 / 重名 / 只读 / 表满"都抛它。</li>
 *   <li><b>为什么用运行时异常</b>：文件操作调用点太多，用受检异常会让签名到处 {@code throws}。</li>
 *   <li><b>分工</b>：成员 B 主讲（顺带说明错误信息为什么能直接显示在命令行里）。</li>
 * </ul>
 */
public class FsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FsException(String message) {
        super(message);
    }

    public FsException(String message, Throwable cause) {
        super(message, cause);
    }
}
