package cn.scau.os.ui;

import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 统一的"卡片"容器：白色圆角底 + 左侧强调条 + 标题。
 *
 * <p>只重写了背景绘制，内容仍用标准 BorderLayout 摆放，
 * 所以里面的表格、滚动条都是原生控件，行为不受影响。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：所有面板的"外壳"：白底圆角卡片 + 左侧强调条 + 标题 + 右上角角标。</li>
 *   <li><b>从哪读起</b>：{@link #paintComponent(java.awt.Graphics)}（圆角与强调条怎么画）→
 *       {@code setBadge(String)}。</li>
 *   <li><b>易错点</b>：角标和标题会抢位置，面板很窄时要先判断剩余宽度，
 *       否则两个文字会叠在一起。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public class CardPanel extends JPanel {

    private final String title;
    private String badge = "";

    public CardPanel(String title) {
        this.title = title;
        setOpaque(false);
        setLayout(new BorderLayout(0, 6));
        // 上边留出 32px 画标题，其余是内容区
        setBorder(new EmptyBorder(32, 12, 10, 12));
    }

    /** 右上角的小字提示，例如 "已用 123/512 B"。 */
    public void setBadge(String badge) {
        this.badge = badge == null ? "" : badge;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();

        // 轻微投影，让卡片从背景里浮起来
        g2.setColor(new Color(15, 23, 42, 12));
        g2.fillRoundRect(2, 3, w - 4, h - 4, 12, 12);

        g2.setColor(Theme.CARD);
        g2.fillRoundRect(1, 1, w - 3, h - 4, 12, 12);
        g2.setColor(Theme.BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(1, 1, w - 3, h - 4, 12, 12);

        // 标题左侧的强调竖条
        g2.setColor(Theme.ACCENT);
        g2.fillRoundRect(11, 11, 3, 13, 2, 2);

        g2.setFont(Theme.FONT_TITLE);
        g2.setColor(Theme.TEXT);
        g2.drawString(title, 21, 22);

        if (!badge.isEmpty()) {
            g2.setFont(Theme.FONT_SMALL);
            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(badge);
            // 面板很窄时宁可少显示，也不要让角标压住标题
            int titleWidth = g2.getFontMetrics(Theme.FONT_TITLE).stringWidth(title);
            boolean enoughRoom = (w - textWidth - 14) > (21 + titleWidth + 12);
            if (enoughRoom) {
                g2.setColor(Theme.MUTED);
                g2.drawString(badge, w - textWidth - 14, 21);
            }
        }
        g2.dispose();
    }
}
