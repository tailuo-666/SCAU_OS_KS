package cn.scau.os.ui;

import cn.scau.os.cmd.CommandResult;
import cn.scau.os.cmd.Shell;
import cn.scau.os.engine.OperatingSystem;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户命令区：命令行输入框 + 彩色输出区。
 *
 * <p>命令由 {@link Shell} 解析执行，与界面按钮完全共用一套逻辑。
 * 输出区用 {@link JTextPane} 而不是 JTextArea，是为了给命令回显、正常输出、
 * 错误提示分别上色（JTextArea 无法对单行着色）。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：命令区。一个输入框 + 一个彩色输出区，
 *       回车后把整行交给 {@code Shell}，把返回的文字按"成功黑色/失败红色"追加显示。</li>
 *   <li><b>从哪读起</b>：输入框的 {@code ActionListener}（只有十几行，是"界面 → 命令层"的完整例子）→
 *       {@code appendOutput(...)}。</li>
 *   <li><b>为什么用 JTextPane 而不用 JTextArea</b>：要按行上不同颜色，就得能改字符属性。</li>
 *   <li><b>分工</b>：成员 D 主讲；成员 C 补充"命令是怎么被解析的"。</li>
 * </ul>
 */
public class CommandPanel extends JPanel {

    private final Shell shell;
    private final OperatingSystem os;
    private final JTextField input = new JTextField();
    private final JTextPane output = new JTextPane();
    private final JLabel promptLabel = new JLabel();
    private final List<String> history = new ArrayList<>();
    private int historyCursor = 0;

    public CommandPanel(OperatingSystem os, Shell shell) {
        this.os = os;
        this.shell = shell;

        setLayout(new BorderLayout(0, 4));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        promptLabel.setFont(Theme.FONT_MONO_BOLD);
        promptLabel.setForeground(Theme.ACCENT);
        promptLabel.setText(shell.getPrompt());
        top.add(promptLabel, BorderLayout.WEST);

        input.setFont(Theme.FONT_CONSOLE);
        input.addActionListener(e -> executeCurrent());
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    recallHistory(-1);
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    recallHistory(1);
                }
            }
        });
        top.add(input, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(smallButton("执行", this::executeCurrent));
        buttons.add(smallButton("帮助", () -> runCommand("help")));
        buttons.add(smallButton("清屏", this::clearOutput));
        top.add(buttons, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);

        output.setEditable(false);
        output.setFont(Theme.FONT_CONSOLE);
        output.setBackground(new Color(0xFAFBFD));
        output.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(output);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        add(scroll, BorderLayout.CENTER);

        print("欢迎使用 SCAU-OS 模拟操作系统。输入 ", Theme.MUTED, false);
        print("help", Theme.ACCENT, true);
        print(" 查看全部命令；命令不区分大小写，路径用 \\ 或 / 均可。\n", Theme.MUTED, false);
    }

    private JButton smallButton(String text, Runnable action) {
        JButton button = new JButton(text);
        Theme.compact(button);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** 执行输入框里的命令。 */
    public void executeCurrent() {
        String line = input.getText();
        input.setText("");
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        history.add(line);
        historyCursor = history.size();
        runCommand(line);
    }

    /** 供其他面板调用：执行一条命令并把结果显示在输出区。 */
    public void runCommand(String line) {
        // ① 先把"提示符 + 用户输入的命令"回显出来，像真的终端那样。
        print(shell.getPrompt() + " " + line + "\n", Theme.ACCENT, true);
        // ② 整行交给 Shell。注意：界面完全不解析命令，只负责把字符串递过去。
        CommandResult result = shell.execute(line);
        if (result.isClearScreen()) {
            clearOutput();       // clear 命令比较特殊：它的"结果"就是清屏本身
            return;
        }
        if (result.hasOutput()) {
            // ③ 有输出就显示。成功的用正文色，失败的用红色并加粗——用户一眼能分清。
            print(result.getMessage() + "\n", result.isSuccess() ? Theme.TEXT : Theme.DANGER,
                    !result.isSuccess());
        }
        // ④ 更新提示符（cd 之后路径会变），并把光标放回输入框，方便连续敲命令
        promptLabel.setText(shell.getPrompt());
        input.requestFocusInWindow();
    }

    /** 一条外部消息（不带命令回显）。 */
    public void print(String text, Color color, boolean bold) {
        StyledDocument doc = output.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, color);
        StyleConstants.setBold(attrs, bold);
        StyleConstants.setFontFamily(attrs, Theme.CONSOLE_FONT_NAME);
        StyleConstants.setFontSize(attrs, 12);
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException e) {
            // 插入失败只影响显示，不掩盖：直接打印到控制台便于排查
            System.err.println("命令输出插入失败：" + e.getMessage());
        }
        output.setCaretPosition(doc.getLength());
    }

    public void clearOutput() {
        output.setText("");
    }

    private void recallHistory(int direction) {
        if (history.isEmpty()) {
            return;
        }
        historyCursor += direction;
        if (historyCursor < 0) {
            historyCursor = 0;
        }
        if (historyCursor > history.size()) {
            historyCursor = history.size();
        }
        input.setText(historyCursor == history.size() ? "" : history.get(historyCursor));
        input.requestFocusInWindow();
    }

    public void focusInput() {
        input.requestFocusInWindow();
    }

    /** 系统时钟变化后刷新提示符（提示符只依赖当前目录，这里顺带更新）。 */
    public void refreshPrompt() {
        promptLabel.setText(shell.getPrompt());
    }
}
