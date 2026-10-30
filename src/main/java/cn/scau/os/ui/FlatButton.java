package cn.scau.os.ui;

import javax.swing.JButton;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 扁平风格按钮。
 *
 * <p>Windows 原生按钮在自定义配色的界面里显得突兀，这里自绘圆角背景，
 * 文字仍交给父类绘制，因此焦点、快捷键、无障碍等行为不受影响。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：去掉默认边框和渐变的自绘按钮，保证 8 个区域的按钮长得一致。</li>
 *   <li><b>从哪读起</b>：{@code paintComponent} 里按"普通/悬停/按下/禁用"四种状态取色。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public class FlatButton extends JButton {

    private final Color normalBackground;
    private final Color hoverBackground;
    private final Color pressedBackground;
    private final Color disabledBackground = new Color(0xE9EDF3);

    public FlatButton(String text) {
        this(text, false);
    }

    public FlatButton(String text, boolean primary) {
        super(text);
        setFont(Theme.FONT_SMALL);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(new EmptyBorder(5, 13, 5, 13));
        if (primary) {
            normalBackground = Theme.ACCENT;
            hoverBackground = new Color(0x1D4ED8);
            pressedBackground = new Color(0x1E40AF);
            setForeground(Color.WHITE);
        } else {
            normalBackground = new Color(0xEFF2F7);
            hoverBackground = new Color(0xE2E8F0);
            pressedBackground = new Color(0xD6DEE9);
            setForeground(Theme.TEXT);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color background;
        if (!isEnabled()) {
            background = disabledBackground;
        } else if (getModel().isPressed()) {
            background = pressedBackground;
        } else if (getModel().isRollover()) {
            background = hoverBackground;
        } else {
            background = normalBackground;
        }
        g2.setColor(background);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
        g2.dispose();
        super.paintComponent(g);
    }
}
