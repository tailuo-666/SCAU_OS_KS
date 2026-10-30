package cn.scau.os;

import cn.scau.os.engine.OperatingSystem;
import cn.scau.os.ui.MainFrame;
import cn.scau.os.ui.Theme;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

/**
 * 程序入口。
 *
 * <p>启动流程：解析工作目录 → 安装界面主题 → 打开（或首次创建）模拟磁盘 →
 * 在事件分发线程上创建主窗口。
 *
 * <p>工作目录默认是当前目录下的 {@code data}，磁盘镜像为 {@code data/disk.img}。
 * 也可以通过命令行参数或系统属性指定，绝不硬编码某台电脑的绝对路径：
 * <pre>
 *   java -jar scau-os.jar                 # 使用 ./data/disk.img
 *   java -jar scau-os.jar D:\myos         # 使用 D:\myos\disk.img
 *   java -Dscauos.home=D:\myos -jar ...   # 同上
 * </pre>
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：程序入口。决定"数据放哪个目录"，然后创建内核、启动窗口。</li>
 *   <li><b>从哪读起</b>：{@link #main(String[])}，一共 60 行，是理解启动流程的最短路径。</li>
 *   <li><b>易错点</b>：工作目录优先级是"命令行参数 > 系统属性 > ./data"，
 *       不要在代码里写死绝对路径，否则换台机器就跑不起来。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Path workDirectory = resolveWorkDirectory(args);

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境没有图形界面，无法启动 SCAU-OS 主窗口。");
            System.err.println("请在有桌面环境的机器上运行，或使用 mvn test 运行核心逻辑测试。");
            System.exit(2);
            return;
        }

        Theme.install();
        OperatingSystem os = new OperatingSystem(workDirectory);
        System.out.println("SCAU-OS 已启动，磁盘镜像：" + workDirectory.resolve(OperatingSystem.DISK_IMAGE_NAME));
        System.out.println("磁盘剩余 " + os.getFileSystem().freeBlocks() + " 块 / 共 "
                + cn.scau.os.fs.Fat.DATA_BLOCK_COUNT + " 块数据区");

        SwingUtilities.invokeLater(() -> new MainFrame(os).launch());
    }

    /**
     * 决定工作目录：命令行参数 > 系统属性 scauos.home > 当前目录下的 data。
     */
    public static Path resolveWorkDirectory(String[] args) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]).toAbsolutePath();
        }
        String property = System.getProperty("scauos.home");
        if (property != null && !property.isBlank()) {
            return Path.of(property).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.dir"), "data").toAbsolutePath();
    }
}
