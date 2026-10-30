package cn.scau.os.ui;

import cn.scau.os.cmd.CommandResult;
import cn.scau.os.cmd.Shell;
import cn.scau.os.engine.OperatingSystem;
import cn.scau.os.engine.SystemSnapshot;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 主窗口：把各监控面板拼成一个仪表盘。
 *
 * <p>布局：
 * <pre>
 *   顶部：控制区（时钟 / 时间片 / 开始 / 暂停 / 单步 / 重置 / 速度 / 自动到达）
 *   左上：进程管理        左下左：存储管理      左下右：设备管理
 *   右上：模拟磁盘文件系统  右下：磁盘使用情况
 *   底部：命令 / 系统日志（标签页）
 * </pre>
 *
 * <p>刷新只有一个入口 {@link #refresh()}：取一份快照，依次交给各面板。
 * 这样所有区域看到的永远是同一时刻的状态。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：主窗口。它只做两件事：把 8 个面板摆到正确位置（布局），
 *       以及在每次时钟触发时把新快照分发给所有面板（刷新）。</li>
 *   <li><b>从哪读起</b>：{@link #buildLayout()}（看懂 8 个区域怎么切）→
 *       {@code refresh(SystemSnapshot)}（看懂所有面板怎么一起更新）。</li>
 *   <li><b>为什么面板不自己定时刷新</b>：只有一个 {@code SimulationDriver} 节拍器，
 *       统一广播快照，这样界面上一秒看到的所有数字都属于同一个时刻。</li>
 *   <li><b>易错点</b>：分割条位置要在窗口 {@code setVisible} 之后用
 *       {@code invokeLater} 设置，否则会被布局管理器覆盖回默认值。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public class MainFrame extends JFrame {

    private final OperatingSystem os;
    private final Shell shell;
    private final SimulationDriver driver;
    private final List<SnapshotAware> panels = new ArrayList<>();
    private JSplitPane centerSplit;
    private JSplitPane leftSplit;
    private JSplitPane rightSplit;
    private JSplitPane memoryDeviceSplit;

    private final CommandPanel commandPanel;

    public MainFrame(OperatingSystem os) {
        super("SCAU-OS 模拟操作系统 — 华南农业大学操作系统课程设计");
        this.os = os;
        this.shell = new Shell(os);

        // 命令层的两个"外部能力"由界面提供：编辑器与确认对话框
        shell.setEditor(path -> EditorDialog.open(this, os.getFileSystem(), path));
        shell.setConfirm((title, message) -> JOptionPane.showConfirmDialog(this, message, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION);

        this.driver = new SimulationDriver(os, this::refresh);

        ProcessPanel processPanel = new ProcessPanel(os, this::refreshAndReload);
        MemoryPanel memoryPanel = new MemoryPanel();
        DevicePanel devicePanel = new DevicePanel();
        FileSystemPanel fileSystemPanel = new FileSystemPanel(os, shell, this::refreshAndReload);
        DiskGridPanel diskPanel = new DiskGridPanel();
        this.commandPanel = new CommandPanel(os, shell);
        LogPanel logPanel = new LogPanel();

        panels.add(processPanel);
        panels.add(memoryPanel);
        panels.add(devicePanel);
        panels.add(fileSystemPanel);
        panels.add(diskPanel);
        panels.add(logPanel);

        ControlPanel controlPanel = new ControlPanel(os, driver, this::refreshAndReload);
        panels.add(controlPanel);

        setLayout(new BorderLayout());
        add(controlPanel, BorderLayout.NORTH);
        add(buildCenter(processPanel, memoryPanel, devicePanel, fileSystemPanel, diskPanel),
                BorderLayout.CENTER);
        add(buildBottom(logPanel), BorderLayout.SOUTH);
        setJMenuBar(buildMenu());

        installShortcuts();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        setSize(1560, 980);
        setMinimumSize(new Dimension(1200, 780));
        setLocationRelativeTo(null);
        refresh();
    }

    private JSplitPane buildCenter(ProcessPanel processPanel, MemoryPanel memoryPanel,
                                   DevicePanel devicePanel, FileSystemPanel fsPanel,
                                   DiskGridPanel diskPanel) {
        JSplitPane memoryAndDevice = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, memoryPanel, devicePanel);
        memoryAndDevice.setResizeWeight(0.5);
        memoryAndDevice.setDividerSize(6);
        memoryAndDevice.setBorder(null);
        memoryAndDevice.setOpaque(false);

        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, processPanel, memoryAndDevice);
        left.setResizeWeight(0.48);
        left.setDividerSize(6);
        left.setBorder(null);
        left.setOpaque(false);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, fsPanel, diskPanel);
        right.setResizeWeight(0.62);
        right.setDividerSize(6);
        right.setBorder(null);
        right.setOpaque(false);

        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        center.setResizeWeight(0.55);
        center.setDividerSize(6);
        center.setBorder(null);
        center.setOpaque(false);

        this.centerSplit = center;
        this.leftSplit = left;
        this.rightSplit = right;
        this.memoryDeviceSplit = memoryAndDevice;
        return center;
    }

    private JTabbedPane buildBottom(LogPanel logPanel) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Theme.FONT_BOLD);
        tabs.addTab("用户命令", commandPanel);
        tabs.addTab("系统日志", logPanel);
        tabs.setPreferredSize(new Dimension(100, 200));
        tabs.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        return tabs;
    }

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();

        JMenu system = new JMenu("系统");
        system.add(menuItem("开始运行", KeyEvent.VK_F5, () -> {
            os.setPaused(false);
            driver.start();
            refresh();
        }));
        system.add(menuItem("暂停", KeyEvent.VK_F6, () -> {
            os.setPaused(true);
            refresh();
        }));
        system.add(menuItem("单步执行", KeyEvent.VK_F7, () -> {
            os.setPaused(true);
            driver.step();
        }));
        system.addSeparator();
        system.add(menuItem("重置（保留磁盘文件）", 0, () -> {
            os.resetSystem();
            refreshAndReload();
        }));
        system.add(menuItem("格式化磁盘…", 0, this::formatDisk));
        system.addSeparator();
        system.add(menuItem("退出", 0, this::shutdown));
        bar.add(system);

        JMenu help = new JMenu("帮助");
        help.add(menuItem("命令帮助", KeyEvent.VK_F1, this::showHelp));
        help.add(menuItem("关于本系统", 0, this::showAbout));
        bar.add(help);
        return bar;
    }

    private JMenuItem menuItem(String text, int acceleratorKey, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (acceleratorKey != 0) {
            item.setAccelerator(KeyStroke.getKeyStroke(acceleratorKey, 0));
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private void installShortcuts() {
        // 焦点不在输入框时也能用功能键控制
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "startSim");
        getRootPane().getActionMap().put("startSim", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                os.setPaused(false);
                driver.start();
                refresh();
            }
        });
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "stepSim");
        getRootPane().getActionMap().put("stepSim", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                os.setPaused(true);
                driver.step();
            }
        });
    }

    // ==================================================================
    // 刷新
    // ==================================================================

    /** 取一份快照并刷新所有面板。 */
    private void refresh() {
        // 只取"一份"快照，然后喂给所有面板。
        // 如果每个面板各自去内核里取，理论上可能取到不同时刻的数据，
        // 界面就会出现"进程表说 P1 在运行、内存表说 P1 没占内存"这种自相矛盾的画面。
        SystemSnapshot snapshot = os.snapshot();
        for (SnapshotAware panel : panels) {
            panel.update(snapshot);   // 多态：主窗口不需要知道每个面板具体是什么
        }
    }

    /** 立即刷新界面（供外部驱动，例如截图脚本或自动化测试）。 */
    public void refreshNow() {
        refreshAndReload();
    }

    /** 磁盘内容变化后的刷新：树要重建、命令提示符要更新。 */
    private void refreshAndReload() {
        commandPanel.refreshPrompt();
        refresh();
    }

    // ==================================================================
    // 菜单动作
    // ==================================================================

    private void formatDisk() {
        int answer = JOptionPane.showConfirmDialog(this,
                "格式化会清除模拟磁盘 disk.img 上的全部文件、目录和 FAT，\n"
                        + "并结束所有正在运行的进程。此操作不可撤销。\n\n确定要格式化吗？",
                "格式化磁盘", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        os.formatDisk(true);
        commandPanel.print("\n[系统] 磁盘已格式化，默认文件树已重新写入。\n", Theme.DANGER, true);
        refreshAndReload();
    }

    private void showHelp() {
        CommandResult result = shell.execute("help");
        JTextArea area = new JTextArea(result.getMessage());
        area.setEditable(false);
        area.setFont(Theme.FONT_CONSOLE);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(720, 520));
        JOptionPane.showMessageDialog(this, scroll, "命令帮助", JOptionPane.PLAIN_MESSAGE);
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "SCAU-OS 模拟操作系统\n\n"
                        + "华南农业大学 · 操作系统课程设计\n"
                        + "用 Java Swing 实现的单用户多道程序模拟操作系统\n\n"
                        + "• 文件管理：256 块 × 64 字节磁盘镜像、FAT 显式链接、树形目录\n"
                        + "• 存储管理：512 字节用户区、首次适配动态分区、空闲区合并\n"
                        + "• 设备管理：A×2、B×3、C×3 独占设备、等待队列、倒计时释放\n"
                        + "• 进程管理：10 个 PCB、时间片轮转、三类中断、闲逛进程\n\n"
                        + "磁盘镜像：" + os.getWorkDirectory().resolve(OperatingSystem.DISK_IMAGE_NAME),
                "关于", JOptionPane.INFORMATION_MESSAGE);
    }

    private void shutdown() {
        int answer = JOptionPane.showConfirmDialog(this,
                "退出后模拟磁盘文件会保留，下次启动自动恢复。确定退出吗？",
                "退出", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        driver.stop();
        os.close();
        dispose();
        System.exit(0);
    }

    /**
     * 按比例设置分隔条位置。
     *
     * <p>必须在窗口真正显示并完成一次布局之后再设置：如果在构造函数里设，
     * Swing 会在首次布局时用首选尺寸把分隔条位置覆盖掉。
     */
    private void applyDefaultDividers() {
        centerSplit.setDividerLocation(0.55);
        leftSplit.setDividerLocation(0.48);
        rightSplit.setDividerLocation(0.62);
        memoryDeviceSplit.setDividerLocation(0.5);
    }

    /** 供 Main 调用的启动入口：先刷新一次再显示，避免白屏。 */
    public void launch() {
        setVisible(true);
        SwingUtilities.invokeLater(this::applyDefaultDividers);
        refresh();
        commandPanel.focusInput();
        commandPanel.print("\n[提示] 按 F5 开始、F6 暂停、F7 单步；也可以在命令框输入 help。\n\n",
                Theme.MUTED, false);
    }

    /** 测试/调试用：当前使用的 Shell（命令层与界面共用同一个实例）。 */
    public Shell getShell() {
        return shell;
    }

}
