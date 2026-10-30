package cn.scau.os.ui;

import cn.scau.os.engine.LogEntry;
import cn.scau.os.engine.LogLevel;
import cn.scau.os.engine.SystemSnapshot;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

/**
 * 日志区：带逻辑时间戳的系统事件流。
 *
 * <p>日志直接来自内核的 {@code EventLog}，因此"创建进程 → 分配内存 → 申请设备 →
 * 阻塞 → 唤醒 → 时间片中断 → 进程结束"的顺序与内部真实发生顺序一致，
 * 不可能出现界面日志早于实际状态的情况。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：日志区。一个 JList + 自定义渲染器，按日志级别给每行上色。</li>
 *   <li><b>从哪读起</b>：{@code update(SystemSnapshot)} 里"取最后 N 条 + 自动滚到底"，
 *       再看渲染器里的颜色映射。</li>
 *   <li><b>易错点</b>：每拍都重建整个列表会闪屏，所以只追加增量。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public class LogPanel extends JPanel implements SnapshotAware {

    private final DefaultListModel<LogEntry> model = new DefaultListModel<>();
    private final JList<LogEntry> list = new JList<>(model);
    private int lastSize = 0;
    private boolean autoScroll = true;

    public LogPanel() {
        setLayout(new BorderLayout(0, 4));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));

        list.setFont(Theme.FONT_CONSOLE);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new LogRenderer());
        list.setFixedCellHeight(20);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        add(scroll, BorderLayout.CENTER);

        JLabel hint = Theme.hint("日志时间戳是模拟系统的逻辑时间（系统时钟），不是真实时间。"
                + " 事件类型：系统 / 进程 / 内存 / 设备 / 文件 / 异常");
        add(hint, BorderLayout.SOUTH);
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        int size = snapshot.logs.size();
        if (size < lastSize) {
            model.clear();
            lastSize = 0;
        }
        for (int i = lastSize; i < size; i++) {
            model.addElement(snapshot.logs.get(i));
        }
        lastSize = size;
        if (autoScroll && !model.isEmpty()) {
            list.ensureIndexIsVisible(model.size() - 1);
        }
    }

    /** 清空界面上的日志（不删除内核日志，下次刷新会重新出现）。 */
    public void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
    }

    /** 按级别着色。 */
    private static class LogRenderer extends JLabel implements ListCellRenderer<LogEntry> {

        LogRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends LogEntry> list, LogEntry entry,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            setText(entry.format());
            setFont(Theme.FONT_CONSOLE);
            if (isSelected) {
                setBackground(Theme.ACCENT_LIGHT);
                setForeground(Theme.TEXT);
            } else {
                setBackground(index % 2 == 0 ? Color.WHITE : new Color(0xF8FAFC));
                setForeground(colorOf(entry.level()));
            }
            return this;
        }

        private Color colorOf(LogLevel level) {
            switch (level) {
                case SYSTEM:
                    return Theme.ACCENT;
                case DEVICE:
                    return new Color(0xB45309);
                case MEMORY:
                    return Theme.TEAL;
                case FILE:
                    return new Color(0x15803D);
                case ERROR:
                    return Theme.DANGER;
                case PROCESS:
                default:
                    return Theme.TEXT;
            }
        }
    }
}
