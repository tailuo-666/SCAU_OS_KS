package cn.scau.os.fs;

import java.util.ArrayList;
import java.util.List;

/**
 * 已打开文件表，最多 5 个表项。
 *
 * <p>表项没有"固定槽位归属"：{@link #allocate(OpenFile)} 找第一个空槽，
 * 关闭时把槽位置空。删除文件前必须检查该表，文件被打开时不允许删除。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：最多 5 项的"已打开文件表"，就是课程里要求的打开文件表。</li>
 *   <li><b>从哪读起</b>：{@link #open(String, int)} → {@link #find(String)} →
 *       {@link #close(String)}。注意 {@code open} 会先查是否已打开（同一个文件不重复登记）。</li>
 *   <li><b>为什么限 5 个</b>：课程要求"打开文件表最多 5 个"，资源有限才好演示报错。</li>
 *   <li><b>分工</b>：成员 B 主讲。</li>
 * </ul>
 */
public class OpenFileTable {

    /** 系统最多同时打开 5 个文件。 */
    public static final int MAX_OPEN_FILES = 5;

    private final OpenFile[] slots = new OpenFile[MAX_OPEN_FILES];

    /** 按路径查找表项，未打开返回 null。 */
    public OpenFile find(String path) {
        for (OpenFile file : slots) {
            if (file != null && file.getPath().equals(path)) {
                return file;
            }
        }
        return null;
    }

    public boolean isOpen(String path) {
        return find(path) != null;
    }

    /** 放入一个表项，表满抛异常。 */
    public void allocate(OpenFile file) {
        if (find(file.getPath()) != null) {
            throw new FsException("文件已被打开：" + file.getPath());
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                slots[i] = file;
                return;
            }
        }
        throw new FsException("已打开文件表已满（最多 " + MAX_OPEN_FILES + " 个），请先关闭其他文件");
    }

    /** 关闭指定路径的文件，返回被移除的表项；未打开返回 null。 */
    public OpenFile close(String path) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].getPath().equals(path)) {
                OpenFile removed = slots[i];
                slots[i] = null;
                return removed;
            }
        }
        return null;
    }

    /** 关闭所有文件（格式化、重置时调用）。 */
    public void closeAll() {
        for (int i = 0; i < slots.length; i++) {
            slots[i] = null;
        }
    }

    /** 当前打开的文件列表（拷贝，界面用）。 */
    public List<OpenFile> list() {
        List<OpenFile> list = new ArrayList<>();
        for (OpenFile file : slots) {
            if (file != null) {
                list.add(file);
            }
        }
        return list;
    }

    public int count() {
        int n = 0;
        for (OpenFile file : slots) {
            if (file != null) {
                n++;
            }
        }
        return n;
    }
}
