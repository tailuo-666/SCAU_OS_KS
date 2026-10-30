package cn.scau.os.ui;

import cn.scau.os.engine.SystemSnapshot;
import cn.scau.os.fs.Disk;
import cn.scau.os.fs.Fat;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 磁盘使用情况区：256 个磁盘块按每行 32 个排成 8 行。
 *
 * <p>为什么是 32×8 而不是 16×16：监控区右下角是一块"宽而矮"的空间，
 * 32 列正好把宽度用满，格子也够大点得中。块号顺序仍是 0~255 从左到右、从上到下。
 *
 * <p>颜色：深灰 = FAT 区，紫 = 根目录，浅灰 = 保留块，接近白 = 空闲，
 * 蓝色 = 已被文件或目录占用。点击任意块，下方显示块号、FAT 内容、所属文件与下一块指针，
 * 这些数据全部来自磁盘镜像的解析结果，不是界面自己编的。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：磁盘使用情况。256 个格子（32 列 × 8 行）对应 256 个物理块，
 *       颜色区分 FAT 区 / 根目录 / 已分配 / 空闲 / 保留，点一下能看到这块归谁。</li>
 *   <li><b>从哪读起</b>：{@code update(SystemSnapshot)} 拿 {@code fatSnapshot()}
 *       与 {@code blockOwners()}，然后逐格上色。</li>
 *   <li><b>怎么讲最直观</b>：跑到 {@code dir} 看文件数，再回来数格子——
 *       "一个文件占几个块"立刻就能看见。</li>
 *   <li><b>易错点</b>：读 FAT 时要区分"表项值"和"块号"，
 *       254 是保留块标记、255 是链结束，都不是块号。</li>
 *   <li><b>分工</b>：成员 D 主讲；成员 B 补充"块为什么这么分布"。</li>
 * </ul>
 */
public class DiskGridPanel extends CardPanel implements SnapshotAware {

    /** 每行的块数。 */
    private static final int COLUMNS = 32;
    /** 行数。 */
    private static final int ROWS = Disk.BLOCK_COUNT / COLUMNS;

    private final DiskGrid grid = new DiskGrid();
    private final JLabel detail = new JLabel("点击任意磁盘块查看详情");

    public DiskGridPanel() {
        super("磁盘使用情况（256 块 × 64 字节）");

        grid.setPreferredSize(new Dimension(560, 150));
        grid.setMinimumSize(new Dimension(240, 96));
        add(grid, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 3));
        bottom.setOpaque(false);
        bottom.add(buildLegend(), BorderLayout.NORTH);
        detail.setFont(Theme.FONT_CONSOLE);
        detail.setForeground(Theme.TEXT);
        bottom.add(detail, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        grid.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int block = grid.blockAt(e.getX(), e.getY());
                if (block >= 0) {
                    grid.setSelected(block);
                    showDetail(block);
                }
            }
        });
    }

    private JPanel buildLegend() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 1));
        panel.setOpaque(false);
        panel.add(legendItem("空闲", Theme.BLOCK_FREE));
        panel.add(legendItem("已分配", Theme.BLOCK_USED));
        panel.add(legendItem("FAT 区", Theme.BLOCK_FAT));
        panel.add(legendItem("根目录", Theme.BLOCK_ROOT));
        panel.add(legendItem("保留", Theme.BLOCK_RESERVED));
        return panel;
    }

    private JPanel legendItem(String text, Color color) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        JComponent swatch = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillRoundRect(0, 3, 10, 9, 3, 3);
                g2.dispose();
            }
        };
        swatch.setPreferredSize(new Dimension(10, 14));
        panel.add(swatch);
        JLabel label = new JLabel(text);
        label.setFont(Theme.FONT_SMALL);
        label.setForeground(Theme.MUTED);
        panel.add(label);
        return panel;
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        // 把整张 FAT 表和"每块归谁"的说明一起交给网格去画。
        // 传整张数组而不是逐个格子上色，是因为 256 格逐格调用会更慢，也更容易画错。
        grid.setData(snapshot.fat, snapshot.blockOwners);
        setBadge("已分配 " + snapshot.usedBlocks + " / " + snapshot.dataBlocks
                + "    空闲 " + snapshot.freeBlocks);
        if (grid.getSelected() >= 0) {
            showDetail(grid.getSelected());   // 用户正看着某一格，刷新时把它的详情也更新掉
        }
    }

    /**
     * 显示"第几块"的详细信息。这是全项目最适合讲"FAT 到底是什么"的地方。
     */
    private void showDetail(int block) {
        int[] fat = grid.getFatData();
        String[] owners = grid.getOwners();
        if (fat == null || block >= fat.length) {
            detail.setText("块 " + block + "：暂无数据");
            return;
        }
        int value = fat[block];      // 这一格存的是"下一块"的编号，不是这一块自己的编号
        String next;
        // 判断顺序很重要：先看"这个块号本身是不是系统保留块"，再看表项的值。
        // 如果反过来写，块号 254 的正常数据块会被误认成"保留块"——本项目真的踩过这个坑。
        if (Fat.isReservedBlock(block)) {
            next = "系统保留(254)";
        } else if (value == Fat.FREE) {
            next = "空闲块";
        } else if (value == Fat.END_OF_CHAIN) {
            next = "链结束(255)";
        } else {
            next = "下一块 → " + value;
        }
        String owner = owners[block] == null || owners[block].isEmpty() ? "（未分配）" : owners[block];
        detail.setText("块 " + block + "    FAT=" + value + "    " + kindName(block, value)
                + "    " + owner + "    " + next);
    }

    private String kindName(int block, int fatValue) {
        if (block == 0 || block == 1 || block == 3 || block == 4) {
            return "FAT 区";
        }
        if (block == Fat.ROOT_DIR_BLOCK) {
            return "根目录";
        }
        if (Fat.isReservedBlock(block)) {
            return "保留块";
        }
        return fatValue == Fat.FREE ? "空闲" : "已分配";
    }

    /** 32×8 的块网格。 */
    private static class DiskGrid extends JComponent {

        private int[] fat;
        private String[] owners;
        private int selected = -1;

        void setData(int[] fat, String[] owners) {
            this.fat = fat;
            this.owners = owners;
            repaint();
        }

        int[] getFatData() {
            return fat;
        }

        String[] getOwners() {
            return owners;
        }

        int getSelected() {
            return selected;
        }

        void setSelected(int block) {
            this.selected = block;
            repaint();
        }

        /** 像素坐标 → 块号，点不到返回 -1。 */
        int blockAt(int px, int py) {
            int size = cellSize();
            int originX = (getWidth() - size * COLUMNS) / 2;
            int originY = (getHeight() - size * ROWS) / 2;
            int col = (px - originX) / size;
            int row = (py - originY) / size;
            if (px < originX || py < originY || col < 0 || col >= COLUMNS || row < 0 || row >= ROWS) {
                return -1;
            }
            return row * COLUMNS + col;
        }

        private int cellSize() {
            int byWidth = Math.max(8, getWidth()) / COLUMNS;
            int byHeight = Math.max(8, getHeight()) / ROWS;
            return Math.max(6, Math.min(byWidth, byHeight));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = cellSize();
            int originX = (getWidth() - size * COLUMNS) / 2;
            int originY = (getHeight() - size * ROWS) / 2;

            for (int block = 0; block < Disk.BLOCK_COUNT; block++) {
                int row = block / COLUMNS;
                int col = block % COLUMNS;
                int x = originX + col * size;
                int y = originY + row * size;
                g2.setColor(colorOf(block));
                g2.fillRoundRect(x + 1, y + 1, size - 2, size - 2, 3, 3);
                if (block == selected) {
                    g2.setColor(Theme.DANGER);
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRoundRect(x + 1, y + 1, size - 3, size - 3, 3, 3);
                }
            }
            g2.dispose();
        }

        private Color colorOf(int block) {
            if (fat == null) {
                return Theme.BLOCK_FREE;
            }
            if (block == 0 || block == 1 || block == 3 || block == 4) {
                return Theme.BLOCK_FAT;
            }
            if (block == Fat.ROOT_DIR_BLOCK) {
                return Theme.BLOCK_ROOT;
            }
            int value = fat[block];
            if (Fat.isReservedBlock(block)) {
                return Theme.BLOCK_RESERVED;
            }
            if (value == Fat.FREE) {
                return Theme.BLOCK_FREE;
            }
            return Theme.BLOCK_USED;
        }
    }
}
