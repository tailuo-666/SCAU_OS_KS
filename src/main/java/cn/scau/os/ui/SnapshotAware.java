package cn.scau.os.ui;

import cn.scau.os.engine.SystemSnapshot;

/**
 * 所有"需要跟着时钟刷新"的面板都实现这个接口。
 *
 * <p>刷新入口只有一个：{@link #update(SystemSnapshot)}。面板拿到的是完整快照，
 * 不允许直接访问内核对象，因此界面永远不会改变业务状态。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：只有一句 {@code update(SystemSnapshot)} 的接口。</li>
 *   <li><b>为什么需要</b>：主窗口不需要知道每个面板是进程表还是磁盘网格，
 *       只要它实现了这个接口，就统一喂一份快照——这就是"多态"的实用价值。</li>
 *   <li><b>分工</b>：成员 C 主讲。</li>
 * </ul>
 */
public interface SnapshotAware {

    void update(SystemSnapshot snapshot);
}
