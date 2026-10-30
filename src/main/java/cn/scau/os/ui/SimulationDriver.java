package cn.scau.os.ui;

import cn.scau.os.engine.OperatingSystem;

import javax.swing.Timer;

/**
 * 模拟时钟驱动器：整个系统唯一的"节拍器"。
 *
 * <p>用一个 {@link javax.swing.Timer} 每 N 毫秒调用一次 {@code OperatingSystem.tick()}。
 * 因为 Swing Timer 的回调在事件分发线程（EDT）上执行，模型推进与界面刷新天然串行，
 * 不会出现"两个线程同时改一个队列"的问题。
 *
 * <p>这里刻意<b>不</b>用 {@code Thread.sleep()} 或后台线程去跑 CPU：
 * 那样既无法复现状态，也无法在测试中精确推进时间。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：唯一的节拍器。一个 {@code javax.swing.Timer} 每隔若干毫秒调一次
 *       {@code OperatingSystem.tick()}，然后通知界面刷新。</li>
 *   <li><b>从哪读起</b>：{@code start()} / {@code pause()} / {@code step()} 三个方法，
 *       分别对应"连续跑""暂停""单步"。</li>
 *   <li><b>为什么单步很有用</b>：演示时可以一拍一拍地讲，每一步的日志和界面都能对上。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public class SimulationDriver {

    private final OperatingSystem os;
    private final Runnable afterTick;
    private final Timer timer;
    private boolean running;

    public SimulationDriver(OperatingSystem os, Runnable afterTick) {
        this.os = os;
        this.afterTick = afterTick;
        this.timer = new Timer(os.getTickIntervalMs(), e -> {
            os.tick();
            afterTick.run();
        });
        this.timer.setCoalesce(true);
    }

    /** 开始推进时钟。 */
    public void start() {
        if (!running) {
            running = true;
            timer.start();
        }
    }

    /** 停止推进时钟（界面仍可操作）。 */
    public void stop() {
        if (running) {
            running = false;
            timer.stop();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** 调整模拟速度：每个逻辑时间单位对应的真实毫秒数。 */
    public void setIntervalMs(int intervalMs) {
        os.setTickIntervalMs(intervalMs);
        timer.setDelay(intervalMs);
        timer.setInitialDelay(intervalMs);
    }

    /** 单步推进一个时间单位（不需要定时器在跑）。 */
    public void step() {
        os.stepOnce();
        afterTick.run();
    }
}
