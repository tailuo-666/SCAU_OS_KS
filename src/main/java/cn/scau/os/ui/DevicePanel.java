package cn.scau.os.ui;

import cn.scau.os.engine.SystemSnapshot;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设备监控区：8 个设备实例（A×2、B×3、C×3）+ 三类设备的等待队列。
 *
 * <p>每张设备卡显示：设备编号、是否空闲、占用进程、剩余使用时间（带进度条）。
 * 倒计时是设备管理模块真实维护的数值，不是界面自己算的动画。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：设备监控区。上半部分是 8 张设备卡片，下半部分是三类设备的等待队列。</li>
 *   <li><b>从哪读起</b>：{@code update(SystemSnapshot)} 里遍历 {@code devices}，
 *       根据"占用者 PID / 剩余拍数"决定卡片颜色和文字。</li>
 *   <li><b>数据从哪来</b>：全部来自 {@code SystemSnapshot.DeviceView}，
 *       面板自己不维护任何状态（面板重启也不会丢数据）。</li>
 *   <li><b>分工</b>：成员 D 主讲。</li>
 * </ul>
 */
public class DevicePanel extends CardPanel implements SnapshotAware {

    private final List<DeviceCard> cards = new ArrayList<>();
    private final JPanel grid = new JPanel(new GridLayout(2, 4, 8, 8));
    private final JPanel waitArea = new JPanel(new GridLayout(3, 1, 0, 2));

    public DevicePanel() {
        super("设备管理（独占设备）");
        grid.setOpaque(false);
        for (int i = 0; i < 8; i++) {
            DeviceCard card = new DeviceCard();
            cards.add(card);
            grid.add(card);
        }
        add(grid, BorderLayout.CENTER);

        waitArea.setOpaque(false);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(waitArea, BorderLayout.CENTER);
        add(wrapper, BorderLayout.SOUTH);
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        // ① 8 张卡片：位置固定（A1 A2 B1 B2 B3 C1 C2 C3），只换每张卡片里的数据。
        //    这样用户不用重新找设备，看颜色和数字变化就行。
        List<SystemSnapshot.DeviceView> devices = snapshot.devices;
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).setView(i < devices.size() ? devices.get(i) : null);
        }

        // ② 等待队列：按 A/B/C 三行显示。这里只能"全删了重建"，
        //    因为队列长度每拍都可能变，Swing 没有"改一行文本"之外的增量做法。
        waitArea.removeAll();
        for (Map.Entry<String, List<String>> entry : snapshot.waitQueues.entrySet()) {
            String text = entry.getValue().isEmpty() ? "（无等待）" : String.join("  ", entry.getValue());
            JLabel label = new JLabel("  " + entry.getKey() + " 类等待：" + text);
            label.setFont(Theme.FONT_SMALL);
            label.setForeground(entry.getValue().isEmpty() ? Theme.MUTED : Theme.STATE_BLOCKED_FG);
            waitArea.add(label);
        }
        waitArea.revalidate();   // 告诉布局管理器"子组件变了，重新算位置"
        waitArea.repaint();      // 再请求重画一次

        setBadge("占用 " + snapshot.deviceBusy + " / " + snapshot.deviceTotal
                + "    A 2 台  B 3 台  C 3 台");
    }

    /** 单个设备卡。 */
    private static class DeviceCard extends JComponent {

        private SystemSnapshot.DeviceView view;

        DeviceCard() {
            setPreferredSize(new Dimension(120, 62));
            setMinimumSize(new Dimension(90, 56));
        }

        void setView(SystemSnapshot.DeviceView view) {
            this.view = view;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();

            boolean busy = view != null && view.busy();
            Color accent = busy ? Theme.ACCENT : new Color(0xB9C3D1);
            g2.setColor(busy ? new Color(0xF2F7FF) : new Color(0xF8FAFC));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
            g2.setColor(busy ? new Color(0xBBD3FA) : Theme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

            if (view == null) {
                g2.dispose();
                return;
            }

            // 内容整体垂直居中：卡片高度会随面板拉伸，靠上对齐会显得空
            int top = Math.max(8, (h - 44) / 2);

            g2.setFont(Theme.FONT_MONO_BOLD);
            g2.setColor(accent);
            g2.drawString(view.id(), 8, top + 12);

            g2.setFont(Theme.FONT_SMALL);
            g2.setColor(Theme.MUTED);
            String stateText = busy ? "占用" : "空闲";
            g2.drawString(stateText, w - g2.getFontMetrics().stringWidth(stateText) - 8, top + 12);

            g2.setFont(Theme.FONT_SMALL);
            g2.setColor(Theme.TEXT);
            String owner = busy ? view.ownerText() : "—";
            g2.drawString(owner, 8, top + 30);

            if (busy) {
                String remain = "剩 " + view.remaining();
                g2.setColor(Theme.WARNING);
                g2.drawString(remain, w - g2.getFontMetrics().stringWidth(remain) - 8, top + 30);

                // 倒计时进度条（按初始时长 9 归一，仅作视觉参考）
                int barWidth = w - 16;
                int filled = (int) Math.round(barWidth * Math.min(1.0, view.remaining() / 9.0));
                g2.setColor(new Color(0xE8EDF5));
                g2.fillRoundRect(8, top + 36, barWidth, 5, 3, 3);
                g2.setColor(Theme.WARNING);
                g2.fillRoundRect(8, top + 36, Math.max(3, filled), 5, 3, 3);
            }
            g2.dispose();
        }
    }
}
