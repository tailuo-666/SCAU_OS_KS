package cn.scau.os.ui;

import cn.scau.os.cmd.CommandResult;
import cn.scau.os.cmd.Shell;
import cn.scau.os.engine.OperatingSystem;
import cn.scau.os.engine.SystemSnapshot;
import cn.scau.os.exec.InstructionCodec;
import cn.scau.os.fs.DirEntry;
import cn.scau.os.fs.DirNode;
import cn.scau.os.fs.FileAttribute;
import cn.scau.os.fs.FileSystem;
import cn.scau.os.fs.FsException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件系统区：目录树 + 文件内容预览 + 常用操作按钮。
 *
 * <p>目录树来自快照（每 tick 重新比对结构，只有结构变了才重建，避免树被折叠）；
 * 文件内容在"选中文件"时按需从磁盘读取——这是一次用户操作，不是定时刷新。
 *
 * <p>所有会改动磁盘的按钮都转成命令交给 {@link Shell}，
 * 与在命令框里敲命令走的是同一条路径，因此不会出现"按钮能删、命令不能删"的差异。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：文件系统区。左边目录树，右边文件内容预览，上面一排操作按钮
 *       （新建/删除/重命名/属性/编辑/运行）。</li>
 *   <li><b>从哪读起</b>：{@code update(SystemSnapshot)}（重建树并尽量保留展开状态）→
 *       按钮动作（每个动作都是一次命令层或文件系统调用，然后立刻刷新）。</li>
 *   <li><b>易错点</b>：每次刷新都重建整棵树会让用户刚展开的目录自动折叠，
 *       所以要记住展开路径再恢复。</li>
 *   <li><b>分工</b>：成员 D 主讲（本项目单个文件行数第二多，511 行）。</li>
 * </ul>
 */
public class FileSystemPanel extends CardPanel implements SnapshotAware {

    private final OperatingSystem os;
    private final Shell shell;
    private final FileSystem fs;
    private final Runnable refreshAction;

    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(treeModel);
    private final JTextArea preview = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private String structureSignature = "";
    private String selectedPath = "\\";
    /** 树是否已经建过：首次建树要全部展开，之后只恢复用户原来的展开状态。 */
    private boolean treeInitialized = false;

    public FileSystemPanel(OperatingSystem os, Shell shell, Runnable refreshAction) {
        super("模拟磁盘文件系统");
        this.os = os;
        this.shell = shell;
        this.fs = os.getFileSystem();
        this.refreshAction = refreshAction;

        add(buildToolbar(), BorderLayout.NORTH);

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.setCellRenderer(new FsTreeRenderer());
        tree.addTreeSelectionListener(e -> {
            Object last = tree.getLastSelectedPathComponent();
            if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof DirNode dir) {
                selectedPath = dir.getPath();
                showPreview(dir.getPath());
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });

        preview.setEditable(false);
        preview.setFont(Theme.FONT_CONSOLE);
        preview.setBackground(new Color(0xFAFBFD));
        preview.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        preview.setLineWrap(false);

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        treeScroll.getViewport().setBackground(Color.WHITE);
        // 给定首选高度，让分割条初始位置就能容下"根 + 7 个目录项"
        treeScroll.setPreferredSize(new Dimension(320, 210));

        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        previewScroll.setPreferredSize(new Dimension(320, 80));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, previewScroll);
        split.setResizeWeight(0.56);
        split.setDividerSize(6);
        split.setBorder(null);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);

        status.setFont(Theme.FONT_SMALL);
        status.setForeground(Theme.MUTED);
        add(status, BorderLayout.SOUTH);
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.setOpaque(false);
        bar.add(button("新建文件", this::createFile));
        bar.add(button("新建目录", this::createDirectory));
        bar.add(button("删除", this::deleteSelected));
        bar.add(button("复制", this::copySelected));
        bar.add(button("编辑", this::editSelected));
        bar.add(button("运行", this::runSelected));
        return bar;
    }

    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        Theme.compact(button);
        button.addActionListener(e -> action.run());
        return button;
    }

    // ==================================================================
    // 树刷新
    // ==================================================================

    @Override
    public void update(SystemSnapshot snapshot) {
        // 关键技巧：不是每拍都重建整棵树，而是先算一个"结构签名"。
        // 签名没变（文件没增删、没改名、长度没变）就什么都不做——
        // 否则每秒重建十几次树，用户刚展开的目录会不断被折叠回去，鼠标都点不住。
        String signature = signatureOf(snapshot.tree);
        if (!signature.equals(structureSignature)) {
            structureSignature = signature;
            rebuildTree(snapshot.tree);
        }
        setBadge("已用 " + snapshot.usedBlocks + " 块    空闲 " + snapshot.freeBlocks
                + " 块    打开文件 " + snapshot.openFileCount + "/5");
    }

    /** 结构签名：只要目录项的名称/长度/属性变了就重建树。 */
    private String signatureOf(DirNode node) {
        StringBuilder sb = new StringBuilder();
        appendSignature(node, sb);
        return sb.toString();
    }

    private void appendSignature(DirNode node, StringBuilder sb) {
        sb.append(node.getPath()).append(':').append(node.getLength())
                .append(':').append(node.getAttribute()).append(';');
        for (DirNode child : node.getChildren()) {
            appendSignature(child, sb);
        }
    }

    private void rebuildTree(DirNode root) {
        Set<String> expanded = collectExpandedPaths();
        boolean firstBuild = !treeInitialized;
        treeInitialized = true;

        DefaultMutableTreeNode newRoot = buildNode(root);
        treeModel.setRoot(newRoot);
        treeModel.reload();

        if (firstBuild) {
            // 默认只展开根目录：根下一层就能看到全部 5 个目录和 2 个文件，
            // 再往下由用户按需展开，避免一上来就把树撑成一条长滚动条
            tree.expandPath(new TreePath(newRoot));
        } else {
            restoreExpanded(new TreePath(newRoot), expanded);
        }
        selectPath(selectedPath);
        showPreview(selectedPath);
    }

    private DefaultMutableTreeNode buildNode(DirNode node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        for (DirNode child : node.getChildren()) {
            treeNode.add(buildNode(child));
        }
        return treeNode;
    }

    private Set<String> collectExpandedPaths() {
        Set<String> paths = new HashSet<>();
        Enumeration<TreePath> expanded = tree.getExpandedDescendants(new TreePath(rootNode));
        if (expanded == null) {
            return paths;
        }
        while (expanded.hasMoreElements()) {
            TreePath path = expanded.nextElement();
            Object last = path.getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof DirNode dir) {
                paths.add(dir.getPath());
            }
        }
        return paths;
    }

    private void restoreExpanded(TreePath rootPath, Set<String> expanded) {
        tree.expandPath(rootPath);
        Enumeration<?> all = rootNode.depthFirstEnumeration();
        while (all.hasMoreElements()) {
            Object next = all.nextElement();
            if (next instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof DirNode dir) {
                if (dir.isDirectory() && expanded.contains(dir.getPath())) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
        }
    }

    private void selectPath(String path) {
        Enumeration<?> all = rootNode.depthFirstEnumeration();
        while (all.hasMoreElements()) {
            Object next = all.nextElement();
            if (next instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof DirNode dir) {
                if (dir.getPath().equals(path)) {
                    TreePath treePath = new TreePath(node.getPath());
                    tree.setSelectionPath(treePath);
                    tree.scrollPathToVisible(treePath);
                    return;
                }
            }
        }
    }

    private DirNode selectedNode() {
        Object last = tree.getLastSelectedPathComponent();
        if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof DirNode dir) {
            return dir;
        }
        return null;
    }

    private String selectedDir() {
        DirNode node = selectedNode();
        if (node == null) {
            return "\\";
        }
        return node.isDirectory() ? node.getPath() : cn.scau.os.fs.PathUtil.parent(node.getPath());
    }

    // ==================================================================
    // 预览
    // ==================================================================

    private void showPreview(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return;
            }
            if (fs.isDirectory(path)) {
                List<DirEntry> entries = fs.list(path);
                StringBuilder sb = new StringBuilder();
                sb.append("目录 ").append(path)
                        .append("    块数 ").append(fs.directoryBlockCount(path))
                        .append(" / 容量 ").append(fs.directoryCapacity(path)).append(" 项\n");
                sb.append("----------------------------------------------------------\n");
                sb.append(cn.scau.os.util.TextUtil.pad("名称", 10))
                        .append(cn.scau.os.util.TextUtil.pad("类型", 6))
                        .append(cn.scau.os.util.TextUtil.pad("属性", 16))
                        .append("起始块  长度\n");
                for (DirEntry entry : entries) {
                    sb.append(cn.scau.os.util.TextUtil.pad(entry.getFullName(), 10))
                            .append(cn.scau.os.util.TextUtil.pad(
                                    entry.isDirectory() ? "目录" : "文件", 6))
                            .append(cn.scau.os.util.TextUtil.pad(
                                    FileAttribute.describe(entry.getAttribute()), 16))
                            .append(String.format("%4d  %5d%n",
                                    entry.getStartBlock(), entry.getLength()));
                }
                if (entries.isEmpty()) {
                    sb.append("（空目录）\n");
                }
                preview.setText(sb.toString());
                preview.setCaretPosition(0);
                return;
            }
            DirEntry entry = fs.stat(path);
            byte[] content = fs.readFile(path);
            StringBuilder sb = new StringBuilder();
            sb.append(path).append("    ").append(content.length).append(" 字节    ")
                    .append(FileAttribute.describe(entry.getAttribute())).append('\n');
            sb.append("----------------------------------------------------------\n");
            if (FileAttribute.isExecutable(entry.getAttribute())) {
                for (int i = 0; i < content.length; i++) {
                    sb.append(String.format("[%02d] %02X  %s%n", i, content[i] & 0xFF,
                            InstructionCodec.decode(content[i]).getText()));
                }
            } else {
                sb.append(new String(content, StandardCharsets.UTF_8));
            }
            preview.setText(sb.toString());
            preview.setCaretPosition(0);
        } catch (FsException e) {
            preview.setText("无法预览：" + e.getMessage());
        }
    }

    // ==================================================================
    // 操作
    // ==================================================================

    private void createFile() {
        String name = JOptionPane.showInputDialog(this,
                "文件名（1~3 个字母/数字 + 1 位扩展名，如 A.T 或 P11.E）：", "新建文件");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        run("create " + join(selectedDir(), name.trim()));
    }

    private void createDirectory() {
        String name = JOptionPane.showInputDialog(this,
                "目录名（1~3 个字母/数字，如 SUB）：", "新建目录");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        run("mkdir " + join(selectedDir(), name.trim()));
    }

    private void deleteSelected() {
        DirNode node = selectedNode();
        if (node == null || "\\".equals(node.getPath())) {
            warn("请先选择一个文件或子目录");
            return;
        }
        String command = node.isDirectory() ? "deldir " : "delete ";
        int answer = JOptionPane.showConfirmDialog(this,
                "确定删除 " + node.getPath() + " 吗？" + (node.isDirectory() ? "（包含其中的全部内容）" : ""),
                "确认删除", JOptionPane.OK_CANCEL_OPTION);
        if (answer == JOptionPane.OK_OPTION) {
            run(command + node.getPath());
            selectedPath = selectedDir();
        }
    }

    private void copySelected() {
        DirNode node = selectedNode();
        if (node == null || node.isDirectory()) {
            warn("请先选择一个文件");
            return;
        }
        String target = JOptionPane.showInputDialog(this,
                "复制到（可写目录或新文件名）：", node.getPath());
        if (target == null || target.trim().isEmpty()) {
            return;
        }
        run("copy " + node.getPath() + " " + target.trim());
    }

    private void editSelected() {
        DirNode node = selectedNode();
        if (node == null || node.isDirectory()) {
            warn("请先选择一个文件");
            return;
        }
        run("edit " + node.getPath());
    }

    private void runSelected() {
        DirNode node = selectedNode();
        if (node == null || node.isDirectory()) {
            warn("请先选择一个可执行文件（.e）");
            return;
        }
        if (!FileAttribute.isExecutable(node.getAttribute())) {
            warn("只有扩展名为 .e 的可执行文件才能运行");
            return;
        }
        run("run " + node.getPath());
    }

    private String join(String dir, String name) {
        return cn.scau.os.fs.PathUtil.join(dir, name.toUpperCase());
    }

    private void run(String command) {
        CommandResult result = shell.execute(command);
        if (result.isSuccess()) {
            status.setForeground(Theme.SUCCESS);
            status.setText("✓ " + firstLine(result.getMessage()));
        } else {
            status.setForeground(Theme.DANGER);
            status.setText("✗ " + result.getMessage());
        }
        structureSignature = "";       // 强制下一 tick 重建树
        refreshAction.run();
        if (selectedNode() != null) {
            showPreview(selectedNode().getPath());
        }
    }

    private String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int idx = text.indexOf('\n');
        return idx < 0 ? text : text.substring(0, idx);
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        tree.setSelectionPath(path);
        JPopupMenu menu = new JPopupMenu();

        DirNode node = selectedNode();
        if (node != null && !node.isDirectory()) {
            menu.add(menuItem("运行此程序", this::runSelected));
            menu.add(menuItem("编辑此文件", this::editSelected));
            menu.add(menuItem("复制此文件", this::copySelected));
            menu.addSeparator();
            menu.add(menuItem("设为只读", () -> setAttribute(node, "readonly")));
            menu.add(menuItem("设为普通", () -> setAttribute(node, "normal")));
            menu.addSeparator();
        }
        if (node != null && node.isDirectory()) {
            menu.add(menuItem("在此新建文件", this::createFile));
            menu.add(menuItem("在此新建目录", this::createDirectory));
            menu.addSeparator();
        }
        menu.add(menuItem("删除", this::deleteSelected));
        menu.show(tree, e.getX(), e.getY());
    }

    private javax.swing.JMenuItem menuItem(String text, Runnable action) {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    private void setAttribute(DirNode node, String attribute) {
        run("change " + node.getPath() + " " + attribute);
    }

    /** 目录树渲染：目录加粗，可执行文件紫色，只读/系统文件加标记。 */
    private static class FsTreeRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof DirNode dir) {
                String label = dir.getName();
                if (!dir.isDirectory()) {
                    if (FileAttribute.isReadOnly(dir.getAttribute())) {
                        label += "  [只读]";
                    }
                    if (FileAttribute.isSystem(dir.getAttribute())) {
                        label += "  [系统]";
                    }
                }
                setText(label);
                setToolTipText(dir.getPath() + (dir.isDirectory() ? ""
                        : "  " + dir.getLength() + " 字节  起始块 " + dir.getStartBlock()));
                if (!selected) {
                    if (dir.isDirectory()) {
                        setForeground("\\".equals(dir.getPath()) ? Theme.ACCENT : Theme.TEXT);
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (FileAttribute.isExecutable(dir.getAttribute())) {
                        setForeground(Theme.PURPLE);
                        setFont(getFont().deriveFont(Font.PLAIN));
                    } else if (FileAttribute.isReadOnly(dir.getAttribute())) {
                        setForeground(Theme.MUTED);
                        setFont(getFont().deriveFont(Font.PLAIN));
                    } else {
                        setForeground(Theme.TEXT);
                        setFont(getFont().deriveFont(Font.PLAIN));
                    }
                }
            }
            return this;
        }
    }
}
