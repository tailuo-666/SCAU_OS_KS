package cn.scau.os.ui;

import cn.scau.os.engine.SystemSnapshot;
import cn.scau.os.mem.MemoryPartition;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 存储监控区：512 字节用户区的分区示意 + 分区表。
 *
 * <p>彩色条不是装饰：每一段的宽度严格等于该分区的字节数，
 * 颜色按进程号取，因此进程申请/释放内存时能直接看到分区的切分与合并。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：存储监控区。一条 512 像素宽的横条代表 512 字节用户区，
 *       每段按 PID 上色，下面配一张"起址 / 长度 / 状态"明细表。</li>
 *   <li><b>从哪读起</b>：{@code update(SystemSnapshot)} 拿到 {@code partitions} 列表，
 *       按 {@code size} 算每段像素宽度。</li>
 *   <li><b>易错点</b>：很小的空闲区如果按真实比例画会不足 1 像素而消失，
 *       所以设了最小宽度 3 像素（宁可失真也要看得见）。</li>
 *   <li><b>分工</b>：成员 D 主讲；成员 A 补充"首次适应法与合并"。这是全组最容易讲出彩的一块。</li>
 * </ul>
 */
public class MemoryPanel extends CardPanel implements SnapshotAware {

    private final MemoryBar bar = new MemoryBar();
    private final PartitionTableModel model = new PartitionTableModel();
    private final JTable table = new JTable(model);
    private final JLabel usageLabel = new JLabel("0 / 512 字节");

    public MemoryPanel() {
        super("存储管理（用户区）");

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        usageLabel.setFont(Theme.FONT_BOLD);
        usageLabel.setForeground(Theme.TEXT);
        top.add(usageLabel, BorderLayout.NORTH);
        bar.setPreferredSize(new Dimension(100, 50));
        top.add(bar, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        for (int i = 0; i < 3; i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(center);
        }
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scrollPane.getViewport().setBackground(Color.WHITE);
        add(scrollPane, BorderLayout.CENTER);
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        // 分区列表先按"起址"升序排一遍：条形图是从左往右画的，
        // 不排序的话同一个进程的分区会在图上跳来跳去，看着像内存错乱。
        List<MemoryPartition> partitions = new ArrayList<>(snapshot.partitions);
        partitions.sort(Comparator.comparingInt(MemoryPartition::getStart));
        bar.setPartitions(partitions);   // 条：按长度算像素宽度
        model.setRows(partitions);       // 表：起址 / 长度 / 状态

        // 使用率用整数运算算百分比，避免浮点数带来的 33.333333% 这种不美观显示
        int percent = snapshot.memoryTotal == 0 ? 0
                : snapshot.memoryUsed * 100 / snapshot.memoryTotal;
        usageLabel.setText("已用 " + snapshot.memoryUsed + " / " + snapshot.memoryTotal
                + " 字节（" + percent + "%）    分区 " + partitions.size());
        // 角标只放最关键的两个数字：还剩多少、用的什么算法
        setBadge("空闲 " + (snapshot.memoryTotal - snapshot.memoryUsed) + "B · 首次适配");
    }

    /** 内存分区的彩色条。 */
    private static class MemoryBar extends JComponent {

        private List<MemoryPartition> partitions = new ArrayList<>();

        void setPartitions(List<MemoryPartition> partitions) {
            this.partitions = partitions;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            int barHeight = height - 16;
            int total = 512;

            g2.setColor(new Color(0xF4F6FA));
            g2.fillRoundRect(0, 0, width, barHeight, 8, 8);

            int x = 0;
            for (MemoryPartition part : partitions) {
                // 至少 3 像素，否则只有几字节的小分区在条形图上完全看不见
                int segmentWidth = Math.max(3,
                        (int) Math.round((double) part.getSize() / total * width));
                boolean free = part.isFree();
                Color base = free ? new Color(0xE2E8F0) : Theme.processColor(part.getOwnerPid());
                g2.setColor(base);
                g2.fillRect(x, 0, segmentWidth - 1, barHeight);

                // 窄分区放不下文字，只上色，避免叠字
                if (segmentWidth >= 34) {
                    g2.setColor(free ? Theme.MUTED : Color.WHITE);
                    g2.setFont(Theme.FONT_SMALL);
                    FontMetrics fm = g2.getFontMetrics();
                    String text = free ? "空闲" : "P" + part.getOwnerPid();
                    g2.drawString(text, x + (segmentWidth - fm.stringWidth(text)) / 2,
                            barHeight / 2 + fm.getAscent() / 2 - 1);
                }
                x += segmentWidth;
            }

            g2.setColor(Theme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, width - 1, barHeight, 8, 8);

            // 刻度
            g2.setFont(Theme.FONT_SMALL);
            g2.setColor(Theme.MUTED);
            g2.drawString("0", 0, height - 2);
            String end = "512";
            g2.drawString(end, width - g2.getFontMetrics().stringWidth(end), height - 2);
            String mid = "256";
            g2.drawString(mid, width / 2 - g2.getFontMetrics().stringWidth(mid) / 2, height - 2);
            g2.dispose();
        }
    }

    /** 分区表模型。 */
    private static class PartitionTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"起始", "结束", "长度(B)", "归属"};
        private List<MemoryPartition> rows = new ArrayList<>();

        void setRows(List<MemoryPartition> rows) {
            this.rows = rows;
            fireTableDataChanged();
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
            MemoryPartition part = rows.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return part.getStart();
                case 1:
                    return part.getEnd() - 1;
                case 2:
                    return part.getSize();
                case 3:
                    return part.isFree() ? "空闲" : "进程 P" + part.getOwnerPid();
                default:
                    return "";
            }
        }
    }
}
