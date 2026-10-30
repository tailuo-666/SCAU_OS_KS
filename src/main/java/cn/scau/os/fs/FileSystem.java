package cn.scau.os.fs;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模拟文件系统：磁盘 + FAT + 树形目录 + 文件操作 + 已打开文件表。
 *
 * <p><b>在模拟操作系统中的角色</b>：它是文件管理模块的门面。上层（命令解释器、GUI、
 * 进程创建）只与本类交互，不直接碰 {@link Disk} 和 {@link Fat}，这样 FAT 的
 * "两段式"物理布局、目录项的 8 字节编码等细节都被封装在内部。
 *
 * <h2>关键数据结构</h2>
 * <ul>
 *   <li><b>根目录</b>：固定占用第 2 块，容量 8 个目录项，不可扩展。</li>
 *   <li><b>子目录</b>：起始块不固定，用 FAT 链串起来，每块 8 个目录项，可动态扩展。</li>
 *   <li><b>文件</b>：至少占 1 块，内容按 64 字节切分后依次放到 FAT 链的各个块里。</li>
 *   <li><b>目录项</b>：8 字节，见 {@link DirEntry}。删除只把名称首字节置 0，不搬家。</li>
 * </ul>
 *
 * <p>所有"业务错误"统一抛 {@link FsException}，消息为中文，可直接展示给用户。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：文件系统的"总门面"。所有文件操作都从这里进，
 *       它在内部协调 {@code Disk}（读写块）、{@code Fat}（找空块、串链）、
 *       {@code DirEntry}（编码目录项）三个零件。</li>
 *   <li><b>从哪读起</b>：按这个顺序读六个方法，就是一次完整的"建文件"旅程：<br>
 *       {@link #createFile(String, byte[], int)} → {@link #locate(String)}（找父目录） →
 *       {@code allocateChain}（找空块）→ {@code writeNewChain}（写数据） →
 *       {@code addEntryToDirectory}（加目录项）→ {@link #readFile(String)}（读回来验证）。</li>
 *   <li><b>目录为什么能超过 8 个文件</b>：目录本身也是一个"文件"，
 *       8 个目录项占 1 块，第 9 个就申请新块并用 FAT 串起来。</li>
 *   <li><b>易错点</b>：根目录固定在第 2 块，它不在数据区（5~254），
 *       所以取它的块链要特殊处理，见 {@link #directoryChain(int)}。</li>
 *   <li><b>分工</b>：成员 B 主讲 60%，成员 A 补充"文件操作如何影响进程加载"。</li>
 * </ul>
 */
public class FileSystem {

    private static final int MAX_DEPTH = 32;

    private final Disk disk;
    private final Fat fat;
    private final OpenFileTable openFiles = new OpenFileTable();

    private FileSystem(Disk disk, Fat fat) {
        this.disk = disk;
        this.fat = fat;
    }

    // ==================================================================
    // 构造 / 打开
    // ==================================================================

    /**
     * 打开磁盘镜像。若镜像尚未格式化，则格式化并安装默认文件树。
     */
    public static FileSystem open(Path imagePath) {
        Disk disk = new Disk(imagePath);
        Fat fat = new Fat(disk);
        fat.load();
        FileSystem fs = new FileSystem(disk, fat);
        if (!fat.isFormatted()) {
            fs.format();
            fs.installDefaultTree();
        }
        return fs;
    }

    /** 重新建立一张全新的磁盘（删掉旧镜像）。 */
    public static FileSystem createFresh(Path imagePath, boolean withDefaultTree) {
        Disk.deleteImage(imagePath);
        Disk disk = new Disk(imagePath);
        Fat fat = new Fat(disk);
        fat.load();
        FileSystem fs = new FileSystem(disk, fat);
        fs.format();
        if (withDefaultTree) {
            fs.installDefaultTree();
        }
        return fs;
    }

    public Disk getDisk() {
        return disk;
    }

    public Fat getFat() {
        return fat;
    }

    public OpenFileTable getOpenFiles() {
        return openFiles;
    }

    // ==================================================================
    // 格式化
    // ==================================================================

    /**
     * 格式化：清空全部磁盘块，重建 FAT 与空根目录，并关闭所有已打开文件。
     * 调用方（命令层 / 界面）负责做二次确认。
     */
    public void format() {
        openFiles.closeAll();
        disk.clear();
        fat.reset();
        disk.clearBlock(Fat.ROOT_DIR_BLOCK);
        disk.flush();
    }

    /** 磁盘是否已经写入过文件系统结构。 */
    public boolean isFormatted() {
        return fat.isFormatted();
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public boolean exists(String path) {
        String p = PathUtil.normalize(path);
        if (PathUtil.isRoot(p)) {
            return true;
        }
        return lookup(p) != null;
    }

    public boolean isDirectory(String path) {
        String p = PathUtil.normalize(path);
        if (PathUtil.isRoot(p)) {
            return true;
        }
        Located located = lookup(p);
        return located != null && located.entry.isDirectory();
    }

    /** 取目录项副本；不存在返回 null。 */
    public DirEntry stat(String path) {
        String p = PathUtil.normalize(path);
        if (PathUtil.isRoot(p)) {
            return DirEntry.rootEntry();
        }
        Located located = lookup(p);
        return located == null ? null : located.entry.copy();
    }

    /** 列出目录内容（目录在前，同级按名称排序）。 */
    public List<DirEntry> list(String dirPath) {
        int start = directoryStartBlock(dirPath);
        List<DirEntry> result = new ArrayList<>();
        for (SlotEntry slot : scanDirectory(start)) {
            if (slot.entry != null) {
                result.add(slot.entry);
            }
        }
        result.sort(Comparator
                .comparing((DirEntry e) -> !e.isDirectory())
                .thenComparing(DirEntry::getFullName));
        return result;
    }

    /** 目录占用的块数（FAT 链长度）。 */
    public int directoryBlockCount(String dirPath) {
        return directoryChain(directoryStartBlock(dirPath)).size();
    }

    /** 目录的目录项容量 = 块数 × 8。 */
    public int directoryCapacity(String dirPath) {
        return directoryBlockCount(dirPath) * DirEntry.ENTRIES_PER_BLOCK;
    }

    // ==================================================================
    // 目录操作
    // ==================================================================

    /** 创建目录。 */
    public void mkdir(String path) {
        String p = PathUtil.normalize(path);
        if (PathUtil.isRoot(p)) {
            throw new FsException("根目录已存在");
        }
        if (exists(p)) {
            throw new FsException("同名文件或目录已存在：" + p);
        }
        String parent = PathUtil.parent(p);
        int parentStart = directoryStartBlock(parent);
        PathUtil.NameParts parts = PathUtil.splitName(PathUtil.fileName(p));
        if (parts.extension != 0) {
            throw new FsException("目录名不能带扩展名：" + PathUtil.fileName(p));
        }
        if (parentStart == Fat.ROOT_DIR_BLOCK && rootEntryCount() >= DirEntry.ENTRIES_PER_BLOCK) {
            throw new FsException("根目录最多 8 个目录项，已满，无法再创建：" + p);
        }
        int block = fat.allocateChain(1);
        if (block < 0) {
            throw new FsException("磁盘空间不足，无法创建目录：" + p);
        }
        disk.clearBlock(block);
        DirEntry entry = new DirEntry(parts.name, (char) 0, FileAttribute.DIRECTORY, block, 0);
        addEntry(parentStart, entry);
    }

    /**
     * 删除空目录。目录非空时报错（这是 rmdir 与 deldir 的区别）。
     */
    public void rmdir(String path) {
        String p = PathUtil.normalize(path);
        Located located = requireLocated(p, "目录");
        if (!located.entry.isDirectory()) {
            throw new FsException("不是目录：" + p);
        }
        if (located.entry.getStartBlock() == Fat.ROOT_DIR_BLOCK) {
            throw new FsException("不能删除根目录");
        }
        if (countEntries(located.entry.getStartBlock()) > 0) {
            throw new FsException("目录非空，rmdir 无法删除：" + p + "（可用 deldir 递归删除）");
        }
        fat.freeChain(located.entry.getStartBlock());
        removeEntry(located.dirStartBlock, located.entryIndex);
    }

    /** 递归删除目录（空目录、非空目录都可以）。 */
    public void deldir(String path) {
        deleteDirectoryRecursive(PathUtil.normalize(path), 0);
    }

    private void deleteDirectoryRecursive(String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw new FsException("目录层级过深，删除中止：" + path);
        }
        Located located = requireLocated(path, "目录");
        if (!located.entry.isDirectory()) {
            throw new FsException("不是目录：" + path);
        }
        if (located.entry.getStartBlock() == Fat.ROOT_DIR_BLOCK) {
            throw new FsException("不能删除根目录");
        }
        // 先处理子项，再回收自己，避免边遍历边修改
        List<DirEntry> children = list(path);
        for (DirEntry child : children) {
            String childPath = PathUtil.join(path, child.getFullName());
            if (child.isDirectory()) {
                deleteDirectoryRecursive(childPath, depth + 1);
            } else {
                if (openFiles.isOpen(childPath)) {
                    throw new FsException("文件正被打开，无法删除：" + childPath);
                }
                fat.freeChain(child.getStartBlock());
                removeEntryByPath(childPath);
            }
        }
        fat.freeChain(located.entry.getStartBlock());
        removeEntry(located.dirStartBlock, located.entryIndex);
    }

    // ==================================================================
    // 文件操作
    // ==================================================================

    /** 创建一个空文件。 */
    public void createFile(String path) {
        createFile(path, new byte[0], FileAttribute.NORMAL);
    }

    /** 创建文件并写入内容。 */
    public void createFile(String path, byte[] content, int attribute) {
        String p = PathUtil.normalize(path);
        if (PathUtil.isRoot(p)) {
            throw new FsException("不能把根目录当作文件创建");
        }
        if (exists(p)) {
            throw new FsException("同名文件或目录已存在：" + p);
        }
        String parent = PathUtil.parent(p);
        int parentStart = directoryStartBlock(parent);
        PathUtil.NameParts parts = PathUtil.splitName(PathUtil.fileName(p));
        if (parentStart == Fat.ROOT_DIR_BLOCK && rootEntryCount() >= DirEntry.ENTRIES_PER_BLOCK) {
            throw new FsException("根目录最多 8 个目录项，已满，无法再创建：" + p);
        }
        int attr = FileAttribute.sanitizeForFile(attribute);
        if (parts.extension == 'e') {
            attr |= FileAttribute.EXECUTABLE;
        }
        int start = writeNewChain(content);
        DirEntry entry = new DirEntry(parts.name, parts.extension, attr, start, content.length);
        try {
            addEntry(parentStart, entry);
        } catch (FsException e) {
            fat.freeChain(start);      // 目录项写不进去，必须把刚分配的块还回去
            throw e;
        }
    }

    /** 整文件读取。 */
    public byte[] readFile(String path) {
        Located located = requireLocated(PathUtil.normalize(path), "文件");
        if (located.entry.isDirectory()) {
            throw new FsException("是目录，不能按文件读取：" + path);
        }
        return readChain(located.entry.getStartBlock(), located.entry.getLength());
    }

    /** 按文本（UTF-8）读取。 */
    public String readText(String path) {
        return new String(readFile(path), StandardCharsets.UTF_8);
    }

    /**
     * 整文件覆盖写入（相当于"保存"）。会先申请新块再释放旧块，
     * 因此磁盘空间不足时原文件保持完好。
     */
    public void writeFile(String path, byte[] content) {
        String p = PathUtil.normalize(path);
        Located located = requireLocated(p, "文件");
        if (located.entry.isDirectory()) {
            throw new FsException("是目录，不能写入：" + p);
        }
        if (FileAttribute.isReadOnly(located.entry.getAttribute())) {
            throw new FsException("文件为只读属性，不能写入：" + p);
        }
        if (openFiles.isOpen(p)) {
            throw new FsException("文件正被打开，请先关闭再保存：" + p);
        }
        int newStart = writeNewChain(content);
        fat.freeChain(located.entry.getStartBlock());
        located.entry.setStartBlock(newStart);
        located.entry.setLength(content.length);
        writeEntry(located.dirStartBlock, located.entryIndex, located.entry);
    }

    /** 以 UTF-8 文本覆盖写入。 */
    public void writeText(String path, String text) {
        writeFile(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 删除文件。文件被打开时拒绝删除。 */
    public void deleteFile(String path) {
        String p = PathUtil.normalize(path);
        Located located = requireLocated(p, "文件");
        if (located.entry.isDirectory()) {
            throw new FsException("是目录，请用 rmdir / deldir 删除：" + p);
        }
        if (openFiles.isOpen(p)) {
            throw new FsException("文件正被打开，不能删除：" + p);
        }
        if (FileAttribute.isReadOnly(located.entry.getAttribute())) {
            throw new FsException("文件为只读属性，不能删除：" + p + "（可先用 change 改为普通）");
        }
        fat.freeChain(located.entry.getStartBlock());
        removeEntry(located.dirStartBlock, located.entryIndex);
    }

    /**
     * 复制文件。
     * <p>dst 若是已存在的目录，则复制到该目录下并沿用源文件名；
     * 若是已存在的文件则覆盖；否则按新文件创建。
     */
    public void copyFile(String srcPath, String dstPath) {
        String src = PathUtil.normalize(srcPath);
        String dst = PathUtil.normalize(dstPath);
        Located source = requireLocated(src, "文件");
        if (source.entry.isDirectory()) {
            throw new FsException("暂不支持复制目录：" + src);
        }
        byte[] content = readChain(source.entry.getStartBlock(), source.entry.getLength());

        String target = resolveCopyTarget(src, dst, source.entry.getFullName());
        if (target == null) {
            throw new FsException("目标目录不存在：" + PathUtil.parent(dst));
        }
        if (exists(target)) {
            Located existing = lookup(target);
            if (existing.entry.isDirectory()) {
                throw new FsException("目标位置已存在同名目录：" + target);
            }
            if (FileAttribute.isReadOnly(existing.entry.getAttribute())) {
                throw new FsException("目标文件为只读属性，不能覆盖：" + target);
            }
            if (openFiles.isOpen(target)) {
                throw new FsException("目标文件正被打开，请先关闭：" + target);
            }
            int newStart = writeNewChain(content);
            fat.freeChain(existing.entry.getStartBlock());
            existing.entry.setStartBlock(newStart);
            existing.entry.setLength(content.length);
            existing.entry.setAttribute(FileAttribute.sanitizeForFile(source.entry.getAttribute()));
            writeEntry(existing.dirStartBlock, existing.entryIndex, existing.entry);
        } else {
            createFile(target, content, FileAttribute.sanitizeForFile(source.entry.getAttribute()));
        }
    }

    /**
     * 移动文件（同一目录内改名，或跨目录移动）。
     */
    public void moveFile(String srcPath, String dstPath) {
        String src = PathUtil.normalize(srcPath);
        String dst = PathUtil.normalize(dstPath);
        Located source = requireLocated(src, "文件");
        if (source.entry.isDirectory()) {
            throw new FsException("暂不支持移动目录：" + src);
        }
        if (FileAttribute.isReadOnly(source.entry.getAttribute())) {
            throw new FsException("文件为只读属性，不能移动：" + src);
        }
        if (openFiles.isOpen(src)) {
            throw new FsException("文件正被打开，不能移动：" + src);
        }
        String target = resolveCopyTarget(src, dst, source.entry.getFullName());
        if (target == null) {
            throw new FsException("目标目录不存在：" + PathUtil.parent(dst));
        }
        if (target.equals(src)) {
            throw new FsException("源路径与目标路径相同：" + src);
        }
        if (exists(target)) {
            throw new FsException("目标已存在同名文件：" + target);
        }
        if (PathUtil.parent(target).equals(PathUtil.parent(src))) {
            // 同一目录内：只改目录项名称，一个磁盘块都不用搬
            PathUtil.NameParts parts = PathUtil.splitName(PathUtil.fileName(target));
            DirEntry renamed = source.entry.copy();
            DirEntry newEntry = new DirEntry(parts.name, parts.extension,
                    renamed.getAttribute(), renamed.getStartBlock(), renamed.getLength());
            removeEntry(source.dirStartBlock, source.entryIndex);
            try {
                addEntry(directoryStartBlock(PathUtil.parent(target)), newEntry);
            } catch (FsException e) {
                addEntry(source.dirStartBlock, source.entry.copy());   // 回滚
                throw e;
            }
        } else {
            copyFile(src, target);
            deleteFile(src);
        }
    }

    /** 修改属性。 */
    public void changeAttribute(String path, int newAttribute) {
        String p = PathUtil.normalize(path);
        Located located = requireLocated(p, "文件或目录");
        int attr = located.entry.isDirectory()
                ? (newAttribute | FileAttribute.DIRECTORY)
                : FileAttribute.sanitizeForFile(newAttribute);
        located.entry.setAttribute(attr);
        writeEntry(located.dirStartBlock, located.entryIndex, located.entry);
    }

    // ==================================================================
    // 已打开文件表：打开 / 关闭 / 读 / 写
    // ==================================================================

    /** 打开文件。mode 为 'r' 或 'w'。 */
    public OpenFile openFile(String path, char mode) {
        String p = PathUtil.normalize(path);
        Located located = requireLocated(p, "文件");
        if (located.entry.isDirectory()) {
            throw new FsException("目录不能被打开：" + p);
        }
        if (mode == OpenFile.MODE_WRITE && FileAttribute.isReadOnly(located.entry.getAttribute())) {
            throw new FsException("文件为只读属性，不能以写方式打开：" + p);
        }
        OpenFile openFile = new OpenFile(p, located.entry.getAttribute(),
                located.entry.getStartBlock(), located.entry.getLength(), mode);
        openFiles.allocate(openFile);
        return openFile;
    }

    /** 关闭文件，并把长度写回目录项。 */
    public void closeFile(String path) {
        String p = PathUtil.normalize(path);
        OpenFile openFile = openFiles.close(p);
        if (openFile == null) {
            throw new FsException("文件未打开：" + p);
        }
        Located located = lookup(p);
        if (located != null) {
            located.entry.setStartBlock(openFile.getStartBlock());
            located.entry.setLength(openFile.getLength());
            writeEntry(located.dirStartBlock, located.entryIndex, located.entry);
        }
    }

    /**
     * 从读指针处读取最多 count 个字节，返回实际读到的内容。
     * 读写都是"块缓冲区"方式：先读整块到 64 字节缓冲，再从缓冲里取需要的部分。
     */
    public byte[] readOpenedFile(String path, int count) {
        String p = PathUtil.normalize(path);
        OpenFile openFile = openFiles.find(p);
        if (openFile == null) {
            throw new FsException("文件未打开：" + p);
        }
        if (count < 0) {
            throw new FsException("读取长度不能为负数");
        }
        int pos = openFile.getReadPointer();
        int remaining = Math.min(count, openFile.getLength() - pos);
        if (remaining <= 0) {
            return new byte[0];
        }
        List<Integer> chain = fat.chainOf(openFile.getStartBlock());
        ByteArrayOutputStream out = new ByteArrayOutputStream(remaining);
        while (remaining > 0) {
            int blockIndex = pos / Disk.BLOCK_SIZE;
            int offsetInBlock = pos % Disk.BLOCK_SIZE;
            if (blockIndex >= chain.size()) {
                break;                       // 链比长度短：数据损坏，止步于此
            }
            byte[] buffer = disk.readBlock(chain.get(blockIndex));
            int n = Math.min(Disk.BLOCK_SIZE - offsetInBlock, remaining);
            out.write(buffer, offsetInBlock, n);
            pos += n;
            remaining -= n;
        }
        openFile.setReadPointer(pos);
        return out.toByteArray();
    }

    /** 从写指针处写入数据，必要时扩展块链。 */
    public void writeOpenedFile(String path, byte[] data) {
        String p = PathUtil.normalize(path);
        OpenFile openFile = openFiles.find(p);
        if (openFile == null) {
            throw new FsException("文件未打开：" + p);
        }
        if (!openFile.isWritable()) {
            throw new FsException("文件以只读方式打开，不能写入：" + p);
        }
        if (data.length == 0) {
            return;
        }
        int pos = openFile.getWritePointer();
        int endPos = pos + data.length;
        int neededBlocks = (endPos + Disk.BLOCK_SIZE - 1) / Disk.BLOCK_SIZE;
        int currentBlocks = fat.chainLength(openFile.getStartBlock());
        while (currentBlocks < neededBlocks) {
            if (fat.appendBlock(openFile.getStartBlock()) < 0) {
                throw new FsException("磁盘空间不足，无法写入：" + p);
            }
            currentBlocks++;
        }
        List<Integer> chain = fat.chainOf(openFile.getStartBlock());
        int written = 0;
        while (written < data.length) {
            int blockIndex = (pos + written) / Disk.BLOCK_SIZE;
            int offsetInBlock = (pos + written) % Disk.BLOCK_SIZE;
            byte[] buffer = disk.readBlock(chain.get(blockIndex));
            int n = Math.min(Disk.BLOCK_SIZE - offsetInBlock, data.length - written);
            System.arraycopy(data, written, buffer, offsetInBlock, n);
            disk.writeBlock(chain.get(blockIndex), buffer);
            written += n;
        }
        openFile.setWritePointer(endPos);
        if (endPos > openFile.getLength()) {
            openFile.setLength(endPos);
        }
    }

    // ==================================================================
    // 统计与可视化数据
    // ==================================================================

    public int freeBlocks() {
        return fat.freeBlockCount();
    }

    public int usedBlocks() {
        return fat.usedBlockCount();
    }

    public int[] fatSnapshot() {
        return fat.snapshot();
    }

    /**
     * 每个磁盘块属于谁：{@code <FAT 区>}、{@code <根目录>}、某个文件路径，或空串表示空闲。
     * 由目录树 + FAT 链推导，不额外存储。
     */
    public String[] blockOwners() {
        String[] owners = new String[Disk.BLOCK_COUNT];
        for (int i = 0; i < owners.length; i++) {
            owners[i] = "";
        }
        owners[0] = "<FAT 区>";
        owners[1] = "<FAT 区>";
        owners[3] = "<FAT 区>";
        owners[4] = "<FAT 区>";
        owners[2] = "<根目录>";
        owners[255] = "<系统保留>";
        markOwners(Fat.ROOT_DIR_BLOCK, PathUtil.ROOT, owners, 0);
        return owners;
    }

    private void markOwners(int dirStart, String dirPath, String[] owners, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (int block : directoryChain(dirStart)) {
            owners[block] = dirPath;
        }
        for (SlotEntry slot : scanDirectory(dirStart)) {
            if (slot.entry == null) {
                continue;
            }
            String childPath = PathUtil.join(dirPath, slot.entry.getFullName());
            if (slot.entry.isDirectory()) {
                markOwners(slot.entry.getStartBlock(), childPath, owners, depth + 1);
            } else {
                for (int block : fat.chainOf(slot.entry.getStartBlock())) {
                    owners[block] = childPath;
                }
            }
        }
    }

    /**
     * 把整棵目录树读成 {@link DirNode}，供界面显示。
     */
    public DirNode buildTree() {
        DirNode root = new DirNode("\\", PathUtil.ROOT, true, FileAttribute.DIRECTORY, 0, Fat.ROOT_DIR_BLOCK);
        fillTree(root, 0);
        return root;
    }

    private void fillTree(DirNode node, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        int start = node.getStartBlock();
        for (SlotEntry slot : scanDirectory(start)) {
            if (slot.entry == null) {
                continue;
            }
            String childPath = PathUtil.join(node.getPath(), slot.entry.getFullName());
            DirNode child = new DirNode(slot.entry.getFullName(), childPath,
                    slot.entry.isDirectory(), slot.entry.getAttribute(),
                    slot.entry.getLength(), slot.entry.getStartBlock());
            node.addChild(child);
            if (slot.entry.isDirectory()) {
                fillTree(child, depth + 1);
            }
        }
    }

    /** 某块被哪个路径占用（给磁盘网格点击查看用）。 */
    public String ownerOfBlock(int blockNo) {
        if (blockNo < 0 || blockNo >= Disk.BLOCK_COUNT) {
            return "";
        }
        return blockOwners()[blockNo];
    }

    // ==================================================================
    // 初始文件树
    // ==================================================================

    /** 安装课程要求的默认目录与文件（5 个目录、15 个文本文件、10 个可执行文件）。 */
    public void installDefaultTree() {
        DefaultFileTree.install(this);
    }

    // ==================================================================
    // 内部：目录项定位与读写
    // ==================================================================

    /** 目录中一个"槽"的信息：全局序号 + 目录项（空槽为 null）。 */
    private static final class SlotEntry {
        final int globalIndex;
        final DirEntry entry;

        SlotEntry(int globalIndex, DirEntry entry) {
            this.globalIndex = globalIndex;
            this.entry = entry;
        }
    }

    /** 定位结果：路径、所在目录起始块、目录项在目录中的全局序号、目录项内容。 */
    public static final class Located {
        public String path;
        public String parentPath;
        public int dirStartBlock;
        public int entryIndex;
        public DirEntry entry;
    }

    /**
     * 目录占用的物理块列表。
     *
     * <p>子目录的块在数据区，可以直接用 FAT 链遍历；但根目录固定在第 2 块，
     * 而第 2 块属于系统保留区（不在 5~254 的数据区范围内），
     * {@link Fat#chainOf(int)} 会拒绝它，所以这里必须特判。
     */
    private List<Integer> directoryChain(int dirStart) {
        if (dirStart == Fat.ROOT_DIR_BLOCK) {
            List<Integer> root = new ArrayList<>(1);
            root.add(Fat.ROOT_DIR_BLOCK);
            return root;
        }
        return fat.chainOf(dirStart);
    }

    /** 扫描一个目录的所有槽位（含空槽，便于找空位与定位删除）。 */
    private List<SlotEntry> scanDirectory(int startBlock) {
        List<SlotEntry> slots = new ArrayList<>();
        List<Integer> chain = directoryChain(startBlock);
        int global = 0;
        for (int blockNo : chain) {
            byte[] buffer = disk.readBlock(blockNo);
            for (int i = 0; i < DirEntry.ENTRIES_PER_BLOCK; i++) {
                int offset = i * DirEntry.SIZE;
                if (DirEntry.isEmptySlot(buffer, offset)) {
                    slots.add(new SlotEntry(global, null));
                } else {
                    slots.add(new SlotEntry(global, DirEntry.decode(buffer, offset)));
                }
                global++;
            }
        }
        return slots;
    }

    /** 目录中非空目录项个数。 */
    private int countEntries(int startBlock) {
        int count = 0;
        for (SlotEntry slot : scanDirectory(startBlock)) {
            if (slot.entry != null) {
                count++;
            }
        }
        return count;
    }

    private int rootEntryCount() {
        return countEntries(Fat.ROOT_DIR_BLOCK);
    }

    /** 解析目录路径，返回其起始块；路径不存在或不是目录时抛异常。 */
    private int directoryStartBlock(String dirPath) {
        String p = PathUtil.normalize(dirPath);
        if (PathUtil.isRoot(p)) {
            return Fat.ROOT_DIR_BLOCK;
        }
        Located located = lookup(p);
        if (located == null) {
            throw new FsException("目录不存在：" + p);
        }
        if (!located.entry.isDirectory()) {
            throw new FsException("不是目录：" + p);
        }
        return located.entry.getStartBlock();
    }

    /** 按段逐级查找目录项。 */
    private Located lookup(String path) {
        String p = PathUtil.normalize(path);
        if (PathUtil.isRoot(p)) {
            return null;
        }
        List<String> segments = PathUtil.segments(p);
        int dirStart = Fat.ROOT_DIR_BLOCK;
        String currentPath = PathUtil.ROOT;
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            PathUtil.NameParts parts = PathUtil.splitName(segment);
            SlotEntry found = findEntry(dirStart, parts.name, parts.extension);
            if (found == null || found.entry == null) {
                return null;
            }
            String childPath = PathUtil.join(currentPath, segment);
            if (i == segments.size() - 1) {
                Located located = new Located();
                located.path = childPath;
                located.parentPath = currentPath;
                located.dirStartBlock = dirStart;
                located.entryIndex = found.globalIndex;
                located.entry = found.entry;
                return located;
            }
            if (!found.entry.isDirectory()) {
                return null;              // 中间段不是目录，整条路径失效
            }
            dirStart = found.entry.getStartBlock();
            currentPath = childPath;
        }
        return null;
    }

    private SlotEntry findEntry(int dirStart, String name, char extension) {
        for (SlotEntry slot : scanDirectory(dirStart)) {
            if (slot.entry != null
                    && slot.entry.getName().equals(name)
                    && slot.entry.getExtension() == extension) {
                return slot;
            }
        }
        return null;
    }

    private Located requireLocated(String path, String what) {
        String p = PathUtil.normalize(path);
        Located located = lookup(p);
        if (located == null) {
            throw new FsException(what + "不存在：" + p);
        }
        return located;
    }

    /** 在目录中找一个空槽写入目录项；目录满了就扩展一块（根目录除外）。 */
    private void addEntry(int dirStart, DirEntry entry) {
        List<Integer> chain = directoryChain(dirStart);
        for (int blockNo : chain) {
            byte[] buffer = disk.readBlock(blockNo);
            for (int i = 0; i < DirEntry.ENTRIES_PER_BLOCK; i++) {
                int offset = i * DirEntry.SIZE;
                if (DirEntry.isEmptySlot(buffer, offset)) {
                    entry.encode(buffer, offset);
                    disk.writeBlock(blockNo, buffer);
                    return;
                }
            }
        }
        if (dirStart == Fat.ROOT_DIR_BLOCK) {
            throw new FsException("根目录最多 " + DirEntry.ENTRIES_PER_BLOCK + " 个目录项，已满");
        }
        int newBlock = fat.appendBlock(dirStart);
        if (newBlock < 0) {
            throw new FsException("磁盘空间不足，无法扩展目录");
        }
        byte[] buffer = new byte[Disk.BLOCK_SIZE];
        entry.encode(buffer, 0);
        disk.writeBlock(newBlock, buffer);
    }

    private void writeEntry(int dirStart, int globalIndex, DirEntry entry) {
        List<Integer> chain = directoryChain(dirStart);
        int blockOrder = globalIndex / DirEntry.ENTRIES_PER_BLOCK;
        int slotInBlock = globalIndex % DirEntry.ENTRIES_PER_BLOCK;
        if (blockOrder >= chain.size()) {
            throw new FsException("目录项位置越界：目录结构可能已损坏");
        }
        byte[] buffer = disk.readBlock(chain.get(blockOrder));
        entry.encode(buffer, slotInBlock * DirEntry.SIZE);
        disk.writeBlock(chain.get(blockOrder), buffer);
    }

    private void removeEntry(int dirStart, int globalIndex) {
        List<Integer> chain = directoryChain(dirStart);
        int blockOrder = globalIndex / DirEntry.ENTRIES_PER_BLOCK;
        int slotInBlock = globalIndex % DirEntry.ENTRIES_PER_BLOCK;
        if (blockOrder >= chain.size()) {
            throw new FsException("目录项位置越界：目录结构可能已损坏");
        }
        byte[] buffer = disk.readBlock(chain.get(blockOrder));
        DirEntry.clearSlot(buffer, slotInBlock * DirEntry.SIZE);
        disk.writeBlock(chain.get(blockOrder), buffer);
    }

    private void removeEntryByPath(String path) {
        Located located = lookup(PathUtil.normalize(path));
        if (located == null) {
            throw new FsException("目录项不存在：" + path);
        }
        removeEntry(located.dirStartBlock, located.entryIndex);
    }

    /** 把内容写成一条新的 FAT 链，返回首块号。 */
    private int writeNewChain(byte[] content) {
        int blocks = Math.max(1, (content.length + Disk.BLOCK_SIZE - 1) / Disk.BLOCK_SIZE);
        int start = fat.allocateChain(blocks);
        if (start < 0) {
            throw new FsException("磁盘空间不足：需要 " + blocks + " 块，当前仅剩 "
                    + fat.freeBlockCount() + " 块");
        }
        List<Integer> chain = fat.chainOf(start);
        for (int i = 0; i < blocks; i++) {
            byte[] buffer = new byte[Disk.BLOCK_SIZE];
            int offset = i * Disk.BLOCK_SIZE;
            int n = Math.min(Disk.BLOCK_SIZE, content.length - offset);
            if (n > 0) {
                System.arraycopy(content, offset, buffer, 0, n);
            }
            disk.writeBlock(chain.get(i), buffer);
        }
        return start;
    }

    /** 沿链读出 length 个字节。 */
    private byte[] readChain(int startBlock, int length) {
        byte[] data = new byte[Math.max(0, length)];
        List<Integer> chain = fat.chainOf(startBlock);
        int copied = 0;
        for (int blockNo : chain) {
            if (copied >= length) {
                break;
            }
            byte[] buffer = disk.readBlock(blockNo);
            int n = Math.min(Disk.BLOCK_SIZE, length - copied);
            System.arraycopy(buffer, 0, data, copied, n);
            copied += n;
        }
        return data;
    }

    /**
     * 计算 copy / move 的真正目标路径。
     * 若 dst 是一个已存在的目录，则把源文件名接在后面。
     */
    private String resolveCopyTarget(String src, String dst, String srcFullName) {
        if (exists(dst) && isDirectory(dst)) {
            return PathUtil.join(dst, srcFullName);
        }
        if (!exists(dst)) {
            String parent = PathUtil.parent(dst);
            if (!exists(parent) || !isDirectory(parent)) {
                return null;
            }
        }
        return dst;
    }

    /** 关闭磁盘镜像。 */
    public void close() {
        disk.close();
    }

    /** 已打开文件数量（界面显示用）。 */
    public int openFileCount() {
        return openFiles.count();
    }
}
