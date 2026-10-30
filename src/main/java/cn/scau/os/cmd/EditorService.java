package cn.scau.os.cmd;

/**
 * 交互式编辑器接口。
 *
 * <p>{@code edit} 命令需要一个可以输入文字的地方：图形界面里是弹出编辑窗口，
 * 没有界面时（单元测试）则不支持。用接口把两者解耦，命令层不必知道 Swing 的存在。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：{@code edit} 命令需要"打开一个编辑窗口"，但命令层不该依赖 Swing。</li>
 *   <li><b>怎么做</b>：定义成接口，界面层注入真正的对话框实现，测试里注入空实现。</li>
 *   <li><b>分工</b>：成员 C 主讲（这是"分层解耦"的第二个例子）。</li>
 * </ul>
 */
@FunctionalInterface
public interface EditorService {

    /**
     * 打开编辑器编辑指定文件。
     *
     * @param path 文件绝对路径
     * @return 是否成功打开了编辑器
     */
    boolean edit(String path);
}
