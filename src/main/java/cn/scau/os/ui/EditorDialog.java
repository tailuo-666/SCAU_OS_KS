package cn.scau.os.ui;

import cn.scau.os.exec.AssemblyException;
import cn.scau.os.exec.InstructionCodec;
import cn.scau.os.fs.DirEntry;
import cn.scau.os.fs.FileAttribute;
import cn.scau.os.fs.FileSystem;
import cn.scau.os.fs.FsException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.nio.charset.StandardCharsets;

/**
 * 文件编辑器。
 *
 * <p>普通文本文件直接编辑原始内容；可执行文件显示为<b>汇编形式</b>
 * （{@code x=10} / {@code !A3} / {@code end}），保存时由 {@link InstructionCodec}
 * 编译成每条 1 字节的机器码写入磁盘，这就是课程要求里"汇编 / 编译的模拟过程"。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：文件编辑器。左边写汇编源码，右边实时显示对应的机器码字节，
 *       保存时先编译再写盘。</li>
 *   <li><b>从哪读起</b>：{@code buildUi()} → 保存按钮里的"编译 → 写文件"两步 → 运行按钮。</li>
 *   <li><b>易错点</b>：汇编写错时必须拦住保存并在行号处报错，
 *       否则会把非法字节写进磁盘，运行到一半才崩。</li>
 *   <li><b>分工</b>：成员 D 主讲；成员 A 补充"一行汇编怎么变成 1 个字节"。</li>
 * </ul>
 */
public class EditorDialog extends JDialog {

    private final FileSystem fs;
    private final String path;
    private final boolean executable;
    private final JTextArea editor = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private boolean saved;

    private EditorDialog(Window owner, FileSystem fs, String path) {
        super(owner, "编辑 " + path, ModalityType.APPLICATION_MODAL);
        this.fs = fs;
        this.path = path;
        DirEntry entry = fs.stat(path);
        this.executable = entry != null && FileAttribute.isExecutable(entry.getAttribute());

        setLayout(new BorderLayout(0, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel hint = new JLabel(executable
                ? "可执行文件：每行一条指令，支持 x=n（0~99）/ x++ / x-- / !A3 / end，保存时编译为单字节机器码"
                : "文本文件：内容以 UTF-8 存入模拟磁盘，超过 64 字节会自动占用多个磁盘块");
        hint.setFont(Theme.FONT_SMALL);
        hint.setForeground(Theme.MUTED);
        add(hint, BorderLayout.NORTH);

        editor.setFont(new Font(Theme.CONSOLE_FONT_NAME, Font.PLAIN, 14));
        editor.setTabSize(4);
        editor.setText(loadText());
        editor.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(editor);
        scroll.setPreferredSize(new Dimension(560, 380));
        add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        status.setFont(Theme.FONT_SMALL);
        status.setForeground(Theme.MUTED);
        status.setText(hintText());
        south.add(status, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton save = new FlatButton("保存并编译", true);
        save.addActionListener(e -> doSave());
        JButton cancel = new FlatButton("取消");
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        buttons.add(save);
        south.add(buttons, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(save);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * 打开编辑器。
     *
     * @return 是否保存过（保存后调用方需要刷新界面）
     */
    public static boolean open(Window owner, FileSystem fs, String path) {
        EditorDialog dialog = new EditorDialog(owner, fs, path);
        dialog.setVisible(true);
        return dialog.saved;
    }

    private String loadText() {
        byte[] content = fs.readFile(path);
        if (executable) {
            return InstructionCodec.disassembleToText(content);
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private String hintText() {
        byte[] content = fs.readFile(path);
        return executable
                ? "当前 " + content.length + " 条指令 / " + content.length + " 字节"
                : "当前 " + content.length + " 字节";
    }

    private void doSave() {
        String text = editor.getText();
        try {
            if (executable) {
                byte[] code = InstructionCodec.assemble(text);
                if (code.length == 0) {
                    throw new FsException("可执行文件至少要有一条指令");
                }
                fs.writeFile(path, code);
                status.setForeground(Theme.SUCCESS);
                status.setText("已保存：" + code.length + " 条指令，占用 "
                        + ((code.length + 63) / 64) + " 个磁盘块");
            } else {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                fs.writeFile(path, data);
                status.setForeground(Theme.SUCCESS);
                status.setText("已保存：" + data.length + " 字节");
            }
            saved = true;
            // 保存成功后停留 300ms 让用户看到提示，再关闭
            javax.swing.Timer closeTimer = new javax.swing.Timer(320, e -> dispose());
            closeTimer.setRepeats(false);
            closeTimer.start();
        } catch (AssemblyException e) {
            status.setForeground(Theme.DANGER);
            status.setText("汇编失败：" + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "汇编错误", JOptionPane.ERROR_MESSAGE);
        } catch (FsException e) {
            status.setForeground(Theme.DANGER);
            status.setText("保存失败：" + e.getMessage());
        }
    }
}
