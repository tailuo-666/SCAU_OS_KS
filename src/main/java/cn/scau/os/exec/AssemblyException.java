package cn.scau.os.exec;

/**
 * 汇编 / 反汇编过程中出现的错误。
 * <p>带行号，方便在编辑可执行文件时定位写错的指令。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：汇编文本写错时抛的异常，消息里带行号，方便编辑器提示。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public class AssemblyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int lineNumber;

    public AssemblyException(int lineNumber, String message) {
        super(lineNumber > 0 ? "第 " + lineNumber + " 行：" + message : message);
        this.lineNumber = lineNumber;
    }

    public int getLineNumber() {
        return lineNumber;
    }
}
