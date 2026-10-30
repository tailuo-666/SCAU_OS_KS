package cn.scau.os.ui;

import cn.scau.os.engine.OperatingSystem;
import cn.scau.os.engine.SystemSnapshot;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * 顶部控制区：时钟、时间片、开始/暂停/继续/单步/重置、速度与自动到达开关。
 *
 * <p>这里的每个按钮都只调用内核的公开方法，界面不保存任何"影子状态"：
 * 按钮是否可用、当前时间片是多少，都从快照读回来。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：顶部控制条：时钟、时间片、开始/暂停/单步/重置、速度、自动到达。</li>
 *   <li><b>从哪读起</b>：按钮的 {@code ActionListener}——每个按钮都只调一个内核或驱动器方法，
 *       没有任何业务逻辑。</li>
 *   <li><b>易错点</b>：改时间片要作用到"下一次调度"，不能让当前进程立刻少一节。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public class ControlPanel extends JPanel implements SnapshotAware {

    private static final String[] SPEED_LABELS = {
            "2.0 秒 / 单位（慢）", "1.0 秒 / 单位", "0.5 秒 / 单位（默认）",
            "0.25 秒 / 单位", "0.1 秒 / 单位（快）"
    };
    private static final int[] SPEED_VALUES = {2000, 1000, 500, 250, 100};

    private final OperatingSystem os;
    private final SimulationDriver driver;
    private final Runnable refreshAction;

    private final JLabel clockValue = new JLabel("0");
    private final JLabel runningValue = new JLabel("-");
    private final JLabel sliceValue = new JLabel("-");
    private final JLabel stateValue = new JLabel("运行中");

    private final JSpinner quantumSpinner = new JSpinner(new SpinnerNumberModel(6, 1, 20, 1));
    private final JComboBox<String> speedCombo = new JComboBox<>(SPEED_LABELS);
    private final JCheckBox autoArrivalBox = new JCheckBox("自动进程到达");

    private final FlatButton startButton = new FlatButton("开始", true);
    private final FlatButton pauseButton = new FlatButton("暂停");
    private final FlatButton resumeButton = new FlatButton("继续");
    private final FlatButton stepButton = new FlatButton("单步");
    private final FlatButton resetButton = new FlatButton("重置");

    /** 程序化修改控件时置 true，避免监听器回写内核造成循环。 */
    private boolean updating;

    public ControlPanel(OperatingSystem os, SimulationDriver driver, Runnable refreshAction) {
        this.os = os;
        this.driver = driver;
        this.refreshAction = refreshAction;

        setOpaque(true);
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(buildFirstRow());
        add(Box.createVerticalStrut(6));
        add(buildSecondRow());

        wireActions();
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    private Component buildFirstRow() {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);

        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        info.setOpaque(false);
        info.add(titleBlock());
        info.add(separator());
        info.add(metric("系统时钟", clockValue, Theme.ACCENT, 24));
        info.add(metric("当前运行", runningValue, Theme.SUCCESS, 18));
        info.add(metric("剩余时间片", sliceValue, Theme.WARNING, 18));
        info.add(metric("系统状态", stateValue, Theme.TEXT, 16));
        row.add(info, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.setOpaque(false);
        buttons.add(startButton);
        buttons.add(pauseButton);
        buttons.add(resumeButton);
        buttons.add(stepButton);
        buttons.add(resetButton);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    private Component buildSecondRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(Theme.hint("时间片"));
        quantumSpinner.setPreferredSize(new Dimension(64, 26));
        quantumSpinner.setFont(Theme.FONT_MONO);
        left.add(quantumSpinner);
        left.add(Theme.hint("个时间单位（下次调度生效）"));
        left.add(Box.createHorizontalStrut(14));
        left.add(Theme.hint("模拟速度"));
        speedCombo.setPreferredSize(new Dimension(170, 26));
        speedCombo.setSelectedIndex(2);
        left.add(speedCombo);
        left.add(Box.createHorizontalStrut(14));
        autoArrivalBox.setOpaque(false);
        autoArrivalBox.setFont(Theme.FONT_SMALL);
        autoArrivalBox.setSelected(true);
        left.add(autoArrivalBox);
        row.add(left, BorderLayout.WEST);

        JLabel hint = Theme.hint("逻辑时间：每条指令 1 个单位");
        row.add(hint, BorderLayout.EAST);
        return row;
    }

    private Component titleBlock() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("SCAU-OS");
        title.setFont(new Font(Theme.UI_FONT_NAME, Font.BOLD, 22));
        title.setForeground(Theme.ACCENT);
        JLabel subtitle = new JLabel("模拟操作系统 · 课程设计");
        subtitle.setFont(Theme.FONT_SMALL);
        subtitle.setForeground(Theme.MUTED);
        panel.add(title);
        panel.add(subtitle);
        panel.setPreferredSize(new Dimension(190, 44));
        return panel;
    }

    private Component separator() {
        JPanel line = new JPanel();
        line.setBackground(Theme.BORDER);
        line.setPreferredSize(new Dimension(1, 34));
        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 4));
        wrapper.setOpaque(false);
        wrapper.add(line);
        return wrapper;
    }

    private Component metric(String title, JLabel value, Color color, int size) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(title);
        name.setFont(Theme.FONT_SMALL);
        name.setForeground(Theme.MUTED);
        value.setFont(new Font(Theme.MONO_FONT_NAME, Font.BOLD, size));
        value.setForeground(color);
        panel.add(name);
        panel.add(value);
        panel.setPreferredSize(new Dimension(size > 20 ? 120 : 108, 44));
        return panel;
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    private void wireActions() {
        startButton.addActionListener(e -> {
            os.setPaused(false);
            driver.start();
            refreshAction.run();
        });
        pauseButton.addActionListener(e -> {
            os.setPaused(true);
            refreshAction.run();
        });
        resumeButton.addActionListener(e -> {
            os.setPaused(false);
            if (!driver.isRunning()) {
                driver.start();
            }
            refreshAction.run();
        });
        stepButton.addActionListener(e -> {
            os.setPaused(true);
            driver.step();
        });
        resetButton.addActionListener(e -> {
            int answer = JOptionPane.showConfirmDialog(this,
                    "重置会结束所有进程并清空内存、设备状态，系统时钟归零。\n磁盘上的文件会保留。确定继续吗？",
                    "重置系统", JOptionPane.OK_CANCEL_OPTION);
            if (answer == JOptionPane.OK_OPTION) {
                os.resetSystem();
                refreshAction.run();
            }
        });

        quantumSpinner.addChangeListener(e -> {
            if (updating) {
                return;
            }
            os.setQuantum((Integer) quantumSpinner.getValue());
            refreshAction.run();
        });

        speedCombo.addActionListener(e -> {
            if (updating) {
                return;
            }
            int index = speedCombo.getSelectedIndex();
            if (index >= 0) {
                driver.setIntervalMs(SPEED_VALUES[index]);
            }
        });

        autoArrivalBox.addActionListener(e -> {
            if (updating) {
                return;
            }
            os.setAutoArrival(autoArrivalBox.isSelected());
        });
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        updating = true;
        clockValue.setText(String.valueOf(snapshot.clock));
        if (snapshot.running == null) {
            runningValue.setText("-");
            sliceValue.setText("-");
        } else if (snapshot.running.idle()) {
            runningValue.setText("闲逛");
            sliceValue.setText("-");
        } else {
            runningValue.setText("P" + snapshot.running.pid());
            sliceValue.setText(snapshot.running.slice() + " / " + snapshot.quantum);
        }
        if (snapshot.paused) {
            stateValue.setText("已暂停");
            stateValue.setForeground(Theme.WARNING);
        } else {
            stateValue.setText("运行中");
            stateValue.setForeground(Theme.SUCCESS);
        }
        if (!Integer.valueOf(snapshot.quantum).equals(quantumSpinner.getValue())) {
            quantumSpinner.setValue(snapshot.quantum);
        }
        autoArrivalBox.setSelected(snapshot.autoArrival);
        startButton.setEnabled(!driver.isRunning());
        pauseButton.setEnabled(!snapshot.paused);
        resumeButton.setEnabled(snapshot.paused);
        updating = false;
    }
}
