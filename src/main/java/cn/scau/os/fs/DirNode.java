package cn.scau.os.fs;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录树节点，只用于"把磁盘上的目录结构读出来给界面展示"。
 *
 * <p>它是一份只读快照：{@link FileSystem#buildTree()} 每次调用都重新扫描磁盘生成，
 * 界面修改它不会影响磁盘上的真实数据。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：只读的目录树节点，专供界面画 JTree 用。</li>
 *   <li><b>为什么要多一层</b>：磁盘上只有"平坦的 8 字节目录项"，界面要的是"树"。
 *       这里把前者的链式结构折叠成后者的父子结构。</li>
 *   <li><b>注意</b>：它是快照，改了它不会改磁盘。真正的写操作在 {@code FileSystem}。</li>
 *   <li><b>分工</b>：成员 B 主讲；成员 D 会问"树上的节点是怎么刷新的"。</li>
 * </ul>
 */
public class DirNode {

    private final String name;
    private final String path;
    private final boolean directory;
    private final int attribute;
    private final int length;
    private final int startBlock;
    private final List<DirNode> children = new ArrayList<>();

    public DirNode(String name, String path, boolean directory, int attribute, int length, int startBlock) {
        this.name = name;
        this.path = path;
        this.directory = directory;
        this.attribute = attribute;
        this.length = length;
        this.startBlock = startBlock;
    }

    public void addChild(DirNode child) {
        children.add(child);
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isDirectory() {
        return directory;
    }

    public int getAttribute() {
        return attribute;
    }

    public int getLength() {
        return length;
    }

    public int getStartBlock() {
        return startBlock;
    }

    public List<DirNode> getChildren() {
        return children;
    }

    @Override
    public String toString() {
        return name;
    }
}
