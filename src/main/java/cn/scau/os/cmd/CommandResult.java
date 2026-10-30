package cn.scau.os.cmd;

/**
 * 一条命令的执行结果。
 *
 * <p>命令层不直接操作界面：成功与否、要显示的文字、是否需要清屏，
 * 都通过本对象交回给界面（或测试代码），因此命令解析可以在没有 GUI 的环境下测试。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一条命令的结果 = 成功与否 + 要显示的文字。</li>
 *   <li><b>为什么单独做类型</b>：界面需要"成功显示黑色、失败显示红色"，
 *       靠 {@code isSuccess()} 就能决定颜色，不用解析字符串。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public class CommandResult {

    private final boolean success;
    private final String message;
    private final boolean clearScreen;

    private CommandResult(boolean success, String message, boolean clearScreen) {
        this.success = success;
        this.message = message;
        this.clearScreen = clearScreen;
    }

    public static CommandResult ok(String message) {
        return new CommandResult(true, message, false);
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, message, false);
    }

    /** 清屏命令的结果：界面收到后清空输出区。 */
    public static CommandResult clear() {
        return new CommandResult(true, "", true);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public boolean isClearScreen() {
        return clearScreen;
    }

    /** 是否成功且有输出。 */
    public boolean hasOutput() {
        return message != null && !message.isEmpty();
    }
}
