package cn.scau.os.cmd;

/**
 * 二次确认接口：危险操作（如格式化磁盘）必须先问用户。
 *
 * <p>图形界面里弹出确认对话框；没有界面时默认返回 false（即"取消"），
 * 这样格式化这种破坏性操作绝不会在无人确认的情况下发生。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一个"要不要确认"的接口。{@code format} 这种危险操作会先用它问一句。</li>
 *   <li><b>为什么用接口</b>：命令行环境下没法弹窗，单元测试里也没人点按钮。
 *       做成接口后，测试传一个"永远点确定"的 lambda 就行。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
@FunctionalInterface
public interface ConfirmService {

    /**
     * @param title   对话框标题
     * @param message 提示内容
     * @return 用户是否确认
     */
    boolean confirm(String title, String message);
}
