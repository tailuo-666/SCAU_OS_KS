package cn.scau.os.ui;

import cn.scau.os.engine.OperatingSystem;
import cn.scau.os.engine.SystemSnapshot;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 进程监控区：当前运行进程的现场 + 全部进程表 + 右键人工干预。
 *
 * <p>表格数据全部来自快照，右键菜单的执行结果直接反映到内核，
 * 界面本身不保存任何进程状态。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：进程监控区。上面一条"当前运行进程的现场"（PC / X / 时间片），
 *       下面一张全部进程表，右键可以人工阻塞/唤醒/杀死某个进程。</li>
 *   <li><b>从哪读起</b>：{@code update(SystemSnapshot)}（把快照塞进表格模型）→
 *       {@code TableModel}（哪个列取哪个字段）→ 右键菜单的三个动作。</li>
 *   <li><b>怎么讲最清楚</b>：先指着"当前运行进程"讲 PC 和 X 怎么随时间片变化，
 *       再切到进程表讲状态列（就绪/运行/阻塞/等待内存/完成）。</li>
 *   <li><b>易错点</b>：闲逛进程（PID 0）不出现在进程表里，否则会让人以为系统多了一个进程。</li>
 *   <li><b>分工</b>：成员 D 主讲；成员 A 补充"这些数字是谁改的"。</li>
 * </ul>
 */
public class ProcessPanel extends CardPanel implements SnapshotAware {

    private static final String[] COLUMNS = {
            "PID", "状态", "程序文件", "PC", "X", "内存", "时间片", "说明"
    };

    private final OperatingSystem os;
    private final Runnable refreshAction;
    private final ProcessTableModel model = new ProcessTableModel();
    private final JTable table = new JTable(model);

    private final JLabel runningName = new JLabel("-");
    private final JLabel runningInstruction = new JLabel("-");
    private final JLabel runningRegisters = new JLabel("-");
    private final JLabel runningSlice = new JLabel("-");
    private final JLabel readyQueue = new JLabel("-");
    private final JLabel blockedQueue = new JLabel("-");

    public ProcessPanel(OperatingSystem os, Runnable refreshAction) {
        super("进程管理");
        this.os = os;
        this.refreshAction = refreshAction;

        JPanel header = new JPanel(new GridLayout(2, 1, 0, 4));
        header.setOpaque(false);

        JPanel line1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        line1.setOpaque(false);
        runningName.setFont(Theme.FONT_HUGE);
        runningName.setForeground(Theme.SUCCESS);
        line1.add(Theme.hint("当前运行"));
        line1.add(runningName);
        line1.add(chip("指令", runningInstruction));
        line1.add(chip("寄存器", runningRegisters));
        line1.add(chip("时间片", runningSlice));

        JPanel line2 = new JPanel(new GridLayout(1, 2, 12, 0));
        line2.setOpaque(false);
        line2.add(queueLabel("就绪队列", readyQueue, Theme.STATE_READY_FG));
        line2.add(queueLabel("阻塞队列", blockedQueue, Theme.STATE_BLOCKED_FG));

        header.add(line1);
        header.add(line2);
        add(header, BorderLayout.NORTH);

        configureTable();
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scrollPane.getViewport().setBackground(Color.WHITE);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void configureTable() {
        table.setRowHeight(26);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(0xEEF1F6));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        int[] widths = {52, 62, 150, 46, 46, 92, 66, 260};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        for (int i : new int[]{0, 1, 3, 4, 6}) {
            table.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }
        table.getColumnModel().getColumn(1).setCellRenderer(new StateRenderer());
        table.getColumnModel().getColumn(7).setCellRenderer(new HintRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) {
            return;
        }
        table.setRowSelectionInterval(row, row);
        SystemSnapshot.PcbView view = model.getRow(row);
        if (view.idle()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem block = new JMenuItem("人工阻塞此进程");
        block.addActionListener(a -> {
            if (os.blockProcess(view.pid(), "人工阻塞")) {
                refreshAction.run();
            } else {
                warn("只能阻塞就绪或运行中的进程");
            }
        });
        JMenuItem resume = new JMenuItem("唤醒此进程");
        resume.addActionListener(a -> {
            if (os.resumeProcess(view.pid())) {
                refreshAction.run();
            } else {
                warn("只有被人工阻塞的进程可以手动唤醒；等待设备的进程必须等设备完成");
            }
        });
        JMenuItem kill = new JMenuItem("撤销此进程（P" + view.pid() + "）");
        kill.addActionListener(a -> {
            int answer = JOptionPane.showConfirmDialog(this,
                    "撤销 P" + view.pid() + " 会回收它的内存与设备，确定吗？",
                    "撤销进程", JOptionPane.OK_CANCEL_OPTION);
            if (answer == JOptionPane.OK_OPTION && os.killProcess(view.pid())) {
                refreshAction.run();
            }
        });
        menu.add(block);
        menu.add(resume);
        menu.addSeparator();
        menu.add(kill);
        menu.show(table, e.getX(), e.getY());
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        // 整个方法只有一件事：把"快照里的数字"抄到"界面的控件"上。
        // 这里没有任何 if 判断业务、没有算法——界面只负责显示，这是本项目的一条硬规矩。
        // ① 进程表：让"正在运行"的那个排最前面，其余按 PID 升序，方便对照观察。
        List<SystemSnapshot.PcbView> rows = new ArrayList<>(snapshot.all);
        rows.sort(Comparator
                .comparing((SystemSnapshot.PcbView v) -> !"运行".equals(v.state()))
                .thenComparingInt(SystemSnapshot.PcbView::pid));
        model.setRows(rows);

        // ② 当前运行进程的现场。空值统统显示成 "-"，避免界面上出现 "PC=null"。
        SystemSnapshot.PcbView running = snapshot.running;
        if (running == null) {
            runningName.setText("-");
            runningInstruction.setText("-");
            runningRegisters.setText("-");
            runningSlice.setText("-");
        } else {
            runningName.setText(running.idle() ? "闲逛进程" : "P" + running.pid());
            runningName.setForeground(running.idle() ? Theme.MUTED : Theme.SUCCESS);
            runningInstruction.setText(running.instruction().isEmpty() ? "-" : running.instruction());
            runningRegisters.setText("PC=" + running.pc() + "  X=" + running.x());
            // "剩余时间片 / 总时间片"，例如 3 / 6，一眼能看出这一轮还剩多少
            runningSlice.setText(running.idle() ? "-" : running.slice() + " / " + snapshot.quantum);
        }

        // ③ 就绪队列与阻塞队列，用箭头串起来表示"排队的先后顺序"
        readyQueue.setText(joinNames(snapshot.ready, " → "));
        List<String> blocked = new ArrayList<>();
        for (SystemSnapshot.PcbView view : snapshot.blocked) {
            blocked.add("P" + view.pid());
        }
        blockedQueue.setText(blocked.isEmpty() ? "（空）" : String.join(" → ", blocked));

        // ④ 标题右侧的小角标，用最少的字告诉用户"现在有几个进程、几个在排队、几个在等内存"
        setBadge("进程 " + snapshot.all.size() + " / 10    就绪 " + snapshot.ready.size()
                + "    阻塞 " + snapshot.blocked.size()
                + (snapshot.pending.isEmpty() ? "" : "    等内存 " + snapshot.pending.size()));
    }

    private String joinNames(List<SystemSnapshot.PcbView> views, String delimiter) {
        if (views.isEmpty()) {
            return "（空）";
        }
        List<String> names = new ArrayList<>();
        for (SystemSnapshot.PcbView view : views) {
            names.add("P" + view.pid());
        }
        return String.join(delimiter, names);
    }

    private JPanel chip(String title, JLabel valueLabel) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        panel.setOpaque(false);
        panel.add(Theme.hint(title));
        valueLabel.setFont(Theme.FONT_MONO_BOLD);
        valueLabel.setForeground(Theme.TEXT);
        panel.add(valueLabel);
        return panel;
    }

    private JPanel queueLabel(String title, JLabel valueLabel, Color color) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(Theme.FONT_SMALL);
        titleLabel.setForeground(color);
        titleLabel.setPreferredSize(new java.awt.Dimension(56, 18));
        valueLabel.setFont(Theme.FONT_BASE);
        valueLabel.setForeground(Theme.TEXT);
        panel.add(titleLabel, BorderLayout.WEST);
        panel.add(valueLabel, BorderLayout.CENTER);
        return panel;
    }

    // ------------------------------------------------------------------
    // 表格模型与渲染器
    // ------------------------------------------------------------------

    private static class ProcessTableModel extends AbstractTableModel {

        private List<SystemSnapshot.PcbView> rows = new ArrayList<>();

        void setRows(List<SystemSnapshot.PcbView> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        SystemSnapshot.PcbView getRow(int index) {
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SystemSnapshot.PcbView view = rows.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return view.name();
                case 1:
                    return view.state();
                case 2:
                    return view.program();
                case 3:
                    return view.pc();
                case 4:
                    return view.x();
                case 5:
                    return view.memoryText();
                case 6:
                    return view.idle() ? "-" : String.valueOf(view.slice());
                case 7:
                    if (!view.reason().isEmpty()) {
                        return view.reason();
                    }
                    return view.lastResult();
                default:
                    return "";
            }
        }
    }

    /** 状态列画成带底色的"标签"。 */
    private static class StateRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected,
                    hasFocus, row, column);
            String state = value == null ? "" : value.toString();
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setFont(Theme.FONT_BOLD);
            label.setForeground(Theme.stateForeground(state));
            label.setBackground(isSelected ? Theme.ACCENT_LIGHT : Theme.stateBackground(state));
            label.setOpaque(true);
            return label;
        }
    }

    /** 说明列用灰色小字，避免抢戏。 */
    private static class HintRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected,
                    hasFocus, row, column);
            label.setFont(Theme.FONT_SMALL);
            label.setForeground(isSelected ? Theme.TEXT : Theme.MUTED);
            return label;
        }
    }
}
