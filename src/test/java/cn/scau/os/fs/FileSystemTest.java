package cn.scau.os.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件管理模块的单元测试。
 *
 * <p>覆盖：默认文件树、跨块读写、FAT 分配与回收、目录扩展、根目录上限、
 * 相对路径、复制/移动/删除、只读保护、已打开文件表、磁盘满、格式化、持久化。
 * 所有用例都直接使用 {@link FileSystem} 的公开接口，不依赖界面。
 */
@DisplayName("文件管理模块")
class FileSystemTest {

    @TempDir
    Path tempDir;

    private Path imagePath;
    private FileSystem fs;

    @BeforeEach
    void setUp() {
        imagePath = tempDir.resolve("disk.img");
        fs = FileSystem.createFresh(imagePath, true);
    }

    @AfterEach
    void tearDown() {
        fs.close();
    }

    // ------------------------------------------------------------------
    // 默认文件树
    // ------------------------------------------------------------------

    @Test
    @DisplayName("格式化后安装默认树：5 个目录 + 15 个以上文件")
    void defaultTreeMeetsCourseRequirement() {
        List<DirEntry> root = fs.list("\\");
        assertEquals(7, root.size(), "根目录应有 5 个目录 + 2 个文件");

        for (String dir : new String[]{"BIN", "DOC", "DAT", "SYS", "TMP"}) {
            assertTrue(fs.exists("\\" + dir), "缺少目录 " + dir);
            assertTrue(fs.isDirectory("\\" + dir), dir + " 应为目录");
        }

        assertEquals(10, fs.list("\\BIN").size(), "BIN 下应有 10 个可执行文件");
        assertEquals(7, fs.list("\\DOC").size(), "DOC 下应有 7 个文本文件");
        assertEquals(3, fs.list("\\DAT").size(), "DAT 下应有 3 个数据文件");
        assertEquals(3, fs.list("\\SYS").size(), "SYS 下应有 3 个系统文件");
        assertEquals(0, fs.list("\\TMP").size(), "TMP 应为空目录");

        int files = countFiles(fs.buildTree());
        assertTrue(files >= 15, "普通文件数应不少于 15，实际 " + files);
    }

    private int countFiles(DirNode node) {
        int n = node.isDirectory() ? 0 : 1;
        for (DirNode child : node.getChildren()) {
            n += countFiles(child);
        }
        return n;
    }

    @Test
    @DisplayName("BIN 下的程序都是可执行属性，且每条指令占 1 字节")
    void programsAreExecutable() {
        for (DirEntry entry : fs.list("\\BIN")) {
            assertTrue(FileAttribute.isExecutable(entry.getAttribute()),
                    entry.getFullName() + " 应为可执行文件");
            assertTrue(entry.getLength() > 0, entry.getFullName() + " 不应为空");
            assertTrue(entry.getLength() <= 16, "单字节指令程序不应过长：" + entry.getLength());
        }
        byte[] code = fs.readFile("\\BIN\\P01.E");
        assertArrayEquals(new byte[]{10, (byte) 102}, code, "P01.E 应为 x=10 与 end");
    }

    // ------------------------------------------------------------------
    // 跨块读写
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超过 64 字节的文件跨块存放，读回内容完全一致")
    void crossBlockFileRoundTrip() {
        DirEntry entry = fs.stat("\\DOC\\N01.T");
        assertNotNull(entry);
        assertEquals(294, entry.getLength(), "N01.T 应为 294 字节");

        List<Integer> chain = fs.getFat().chainOf(entry.getStartBlock());
        assertEquals(5, chain.size(), "294 字节应占用 5 个磁盘块（64×4=256 < 294）");

        String text = fs.readText("\\DOC\\N01.T");
        assertTrue(text.startsWith("模拟操作系统课程设计"), "跨块读取后开头内容应保持不变");
        assertTrue(text.endsWith("也可以用 edit 修改它。\n"), "跨块读取后结尾内容应保持不变");

        byte[] raw = fs.readFile("\\DOC\\N01.T");
        assertEquals(entry.getLength(), raw.length);
        assertEquals(294, new String(raw, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("写入 300 字节后读回一致，且块链长度正确")
    void writeLargeContentThenReadBack() {
        byte[] content = new byte[300];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ('A' + i % 26);
        }
        fs.createFile("\\TMP\\BIG.T", content, FileAttribute.NORMAL);

        DirEntry entry = fs.stat("\\TMP\\BIG.T");
        assertEquals(300, entry.getLength());
        assertEquals(5, fs.getFat().chainOf(entry.getStartBlock()).size());
        assertArrayEquals(content, fs.readFile("\\TMP\\BIG.T"));

        fs.writeText("\\TMP\\BIG.T", "短内容");
        assertEquals("短内容", fs.readText("\\TMP\\BIG.T"));
        assertEquals(9, fs.stat("\\TMP\\BIG.T").getLength(), "3 个汉字 = 9 字节");
    }

    // ------------------------------------------------------------------
    // FAT 分配与回收
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建文件消耗块，删除文件全部块归还")
    void deleteReleasesAllBlocks() {
        int before = fs.freeBlocks();
        byte[] content = new byte[200];
        fs.createFile("\\TMP\\X.T", content, FileAttribute.NORMAL);
        assertEquals(before - 4, fs.freeBlocks(), "200 字节应占用 4 块");

        fs.deleteFile("\\TMP\\X.T");
        assertEquals(before, fs.freeBlocks(), "删除后应全部归还");
        assertFalse(fs.exists("\\TMP\\X.T"));
        assertNull(fs.stat("\\TMP\\X.T"));
    }

    @Test
    @DisplayName("已用块 + 空闲块 = 数据区 250 块")
    void blockAccountingIsBalanced() {
        assertEquals(Fat.DATA_BLOCK_COUNT, fs.usedBlocks() + fs.freeBlocks());
        fs.createFile("\\TMP\\A.T", new byte[130], FileAttribute.NORMAL);
        assertEquals(Fat.DATA_BLOCK_COUNT, fs.usedBlocks() + fs.freeBlocks());
    }

    @Test
    @DisplayName("目录项超过 8 个时自动申请第二个磁盘块")
    void directoryGrowsToSecondBlock() {
        assertEquals(10, fs.list("\\BIN").size());
        assertEquals(2, fs.directoryBlockCount("\\BIN"), "10 个目录项应占 2 个块");
        assertEquals(16, fs.directoryCapacity("\\BIN"));
        assertEquals(1, fs.directoryBlockCount("\\DOC"), "7 个目录项只需 1 个块");
    }

    @Test
    @DisplayName("根目录固定 8 个目录项，写满后拒绝新建")
    void rootDirectoryHasEntryLimit() {
        assertEquals(7, fs.list("\\").size());
        fs.mkdir("\\D6");
        assertEquals(8, fs.list("\\").size());
        FsException e = assertThrows(FsException.class, () -> fs.mkdir("\\D7"));
        assertTrue(e.getMessage().contains("根目录"), "错误信息应说明根目录已满");
        assertEquals(1, fs.directoryBlockCount("\\"), "根目录不能扩展");
    }

    // ------------------------------------------------------------------
    // 目录与路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空目录可以删除，非空目录 rmdir 报错、deldir 递归删除")
    void removeDirectoryRules() {
        fs.rmdir("\\TMP");
        assertFalse(fs.exists("\\TMP"));

        FsException e = assertThrows(FsException.class, () -> fs.rmdir("\\DOC"));
        assertTrue(e.getMessage().contains("非空"), "非空目录应提示非空");

        int before = fs.freeBlocks();
        fs.deldir("\\DOC");
        assertFalse(fs.exists("\\DOC"));
        assertTrue(fs.freeBlocks() > before, "递归删除应回收磁盘块");
    }

    @Test
    @DisplayName("相对路径按当前目录解析，支持 / 与 \\ 混用")
    void pathResolution() {
        assertEquals("\\DOC\\N01.T", PathUtil.resolve("\\DOC", "N01.T"));
        assertEquals("\\DOC\\N01.T", PathUtil.resolve("\\DOC", "/DOC/N01.t"));
        assertEquals("\\", PathUtil.resolve("\\DOC", "\\"));
        assertTrue(fs.exists("\\doc\\n01.t"), "路径应大小写不敏感");
        assertTrue(fs.isDirectory("\\BIN\\"));
        assertEquals("\\BIN", PathUtil.parent("\\BIN\\P01.E"));
    }

    // ------------------------------------------------------------------
    // 复制 / 移动 / 属性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("复制文件后两份内容独立")
    void copyFileIsIndependent() {
        fs.copyFile("\\DOC\\N07.T", "\\TMP\\C.T");
        assertEquals("ab\n", fs.readText("\\TMP\\C.T"));

        fs.writeText("\\TMP\\C.T", "changed");
        assertEquals("changed", fs.readText("\\TMP\\C.T"));
        assertEquals("ab\n", fs.readText("\\DOC\\N07.T"), "源文件不应被修改");
    }

    @Test
    @DisplayName("同一目录内移动文件等同于改名，不搬动磁盘块")
    void moveWithinDirectoryKeepsBlocks() {
        int start = fs.stat("\\DOC\\N07.T").getStartBlock();
        fs.moveFile("\\DOC\\N07.T", "\\DOC\\N08.T");
        assertFalse(fs.exists("\\DOC\\N07.T"));
        DirEntry moved = fs.stat("\\DOC\\N08.T");
        assertNotNull(moved);
        assertEquals(start, moved.getStartBlock(), "改名字不应重新分配磁盘块");
        assertEquals("ab\n", fs.readText("\\DOC\\N08.T"));
    }

    @Test
    @DisplayName("只读文件不可写、不可删；改为普通后可删")
    void readOnlyProtection() {
        assertTrue(FileAttribute.isReadOnly(fs.stat("\\DOC\\N06.T").getAttribute()));
        assertThrows(FsException.class, () -> fs.writeText("\\DOC\\N06.T", "x"));
        assertThrows(FsException.class, () -> fs.deleteFile("\\DOC\\N06.T"));

        fs.changeAttribute("\\DOC\\N06.T", FileAttribute.NORMAL);
        fs.deleteFile("\\DOC\\N06.T");
        assertFalse(fs.exists("\\DOC\\N06.T"));
    }

    // ------------------------------------------------------------------
    // 已打开文件表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已打开文件表最多 5 项，关闭后可再打开")
    void openFileTableLimitIsFive() {
        String[] paths = {"\\DOC\\N01.T", "\\DOC\\N02.T", "\\DOC\\N03.T",
                "\\DOC\\N04.T", "\\DOC\\N05.T", "\\DOC\\N07.T"};
        for (int i = 0; i < 5; i++) {
            fs.openFile(paths[i], OpenFile.MODE_READ);
        }
        assertEquals(5, fs.openFileCount());
        FsException e = assertThrows(FsException.class,
                () -> fs.openFile(paths[5], OpenFile.MODE_READ));
        assertTrue(e.getMessage().contains("已满"), "第 6 个文件应提示表已满");

        fs.closeFile(paths[0]);
        assertEquals(4, fs.openFileCount());
        fs.openFile(paths[5], OpenFile.MODE_READ);
        assertEquals(5, fs.openFileCount());
    }

    @Test
    @DisplayName("打开的文件不能被删除或覆盖写入")
    void openedFileIsProtected() {
        fs.openFile("\\DOC\\N07.T", OpenFile.MODE_READ);
        assertThrows(FsException.class, () -> fs.deleteFile("\\DOC\\N07.T"));
        assertThrows(FsException.class, () -> fs.writeText("\\DOC\\N07.T", "x"));
        fs.closeFile("\\DOC\\N07.T");
        fs.deleteFile("\\DOC\\N07.T");
    }

    @Test
    @DisplayName("按读指针分块读取，读指针自动前移")
    void readOpenedFileAdvancesPointer() {
        fs.openFile("\\DOC\\N01.T", OpenFile.MODE_READ);
        // 一个汉字在 UTF-8 中占 3 字节，读 30 字节正好是前 10 个汉字
        byte[] first = fs.readOpenedFile("\\DOC\\N01.T", 30);
        assertEquals(30, first.length);
        assertEquals("模拟操作系统课程设计", new String(first, StandardCharsets.UTF_8));

        byte[] rest = fs.readOpenedFile("\\DOC\\N01.T", 10000);
        assertEquals(264, rest.length, "剩余应为 294 - 30 字节");
        assertEquals(0, fs.readOpenedFile("\\DOC\\N01.T", 10).length, "读到末尾后应返回空");
        fs.closeFile("\\DOC\\N01.T");
    }

    @Test
    @DisplayName("以只读方式打开的文件不能写入")
    void readOnlyModeRejectsWrite() {
        fs.openFile("\\DOC\\N07.T", OpenFile.MODE_READ);
        assertThrows(FsException.class, () -> fs.writeOpenedFile("\\DOC\\N07.T", new byte[]{1}));
        fs.closeFile("\\DOC\\N07.T");
    }

    // ------------------------------------------------------------------
    // 磁盘满 / 格式化 / 持久化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("磁盘写满后继续创建文件报错，删除后可继续使用")
    void diskFullThenRecover() {
        int free = fs.freeBlocks();
        byte[] fill = new byte[free * 64];
        fs.createFile("\\TMP\\FUL.T", fill, FileAttribute.NORMAL);
        assertEquals(0, fs.freeBlocks());

        FsException e = assertThrows(FsException.class, () -> fs.createFile("\\TMP\\MOR.T"));
        assertTrue(e.getMessage().contains("空间不足"), "应提示磁盘空间不足");

        fs.deleteFile("\\TMP\\FUL.T");
        assertEquals(free, fs.freeBlocks());
        fs.createFile("\\TMP\\MOR.T");
        assertTrue(fs.exists("\\TMP\\MOR.T"));
    }

    @Test
    @DisplayName("回归：块链跨过第 254 块时不能被截断（FAT 单字节标记与块号撞号）")
    void chainCrossingBlock254IsNotTruncated() {
        int free = fs.freeBlocks();
        byte[] fill = new byte[free * 64];
        fs.createFile("\\TMP\\FIL.T", fill, FileAttribute.NORMAL);

        DirEntry entry = fs.stat("\\TMP\\FIL.T");
        List<Integer> chain = fs.getFat().chainOf(entry.getStartBlock());
        assertEquals(free, chain.size(), "链长应等于文件块数，第 254 块必须被算进去");
        assertTrue(chain.contains(Fat.LAST_DATA_BLOCK), "最后一个数据块 254 应在链中");
        assertArrayEquals(fill, fs.readFile("\\TMP\\FIL.T"), "写满磁盘的文件读回应完全一致");

        fs.deleteFile("\\TMP\\FIL.T");
        assertEquals(free, fs.freeBlocks(), "含第 254 块的整条链都应被释放");
    }

    @Test
    @DisplayName("格式化后为空盘，FAT 保留块标记正确")
    void formatLeavesEmptyDisk() {
        FileSystem empty = FileSystem.createFresh(tempDir.resolve("empty.img"), false);
        try {
            assertEquals(0, empty.list("\\").size());
            assertEquals(Fat.DATA_BLOCK_COUNT, empty.freeBlocks());
            assertTrue(empty.isFormatted());
            assertNull(empty.stat("\\BIN"));
        } finally {
            empty.close();
        }

        fs.format();
        assertEquals(0, fs.list("\\").size(), "format() 之后应没有任何目录项");
        assertEquals(0, fs.openFileCount(), "格式化应关闭所有已打开文件");
    }

    @Test
    @DisplayName("关闭后重新打开同一镜像，文件内容与 FAT 链保持完整")
    void persistenceAcrossRestart() {
        byte[] content = new byte[150];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 200);
        }
        fs.createFile("\\TMP\\KEP.T", content, FileAttribute.NORMAL);
        int freeBefore = fs.freeBlocks();
        int startBefore = fs.stat("\\TMP\\KEP.T").getStartBlock();
        fs.close();

        FileSystem reopened = FileSystem.open(imagePath);
        try {
            assertTrue(reopened.exists("\\TMP\\KEP.T"));
            assertArrayEquals(content, reopened.readFile("\\TMP\\KEP.T"));
            assertEquals(startBefore, reopened.stat("\\TMP\\KEP.T").getStartBlock());
            assertEquals(freeBefore, reopened.freeBlocks(), "空闲块数应与关闭前一致");
            assertEquals(10, reopened.list("\\BIN").size(), "默认文件树也应完整保留");
        } finally {
            reopened.close();
        }
        // 让 tearDown 不重复关闭
        fs = FileSystem.open(imagePath);
    }

    @Test
    @DisplayName("根目录固定在第 2 块，FAT 前 128 项在 0、1 块，后 128 项在 3、4 块")
    void fatLayoutIsVerifiable() {
        assertEquals(2, Fat.ROOT_DIR_BLOCK);
        assertEquals(0, Fat.blockOfEntry(0));
        assertEquals(0, Fat.blockOfEntry(63), "表项 0~63 在第 0 块");
        assertEquals(1, Fat.blockOfEntry(64), "表项 64~127 在第 1 块");
        assertEquals(1, Fat.blockOfEntry(127));
        assertEquals(3, Fat.blockOfEntry(128));
        assertEquals(4, Fat.blockOfEntry(255));
        assertEquals(5, Fat.FIRST_DATA_BLOCK);
        assertEquals(254, Fat.LAST_DATA_BLOCK);
        assertEquals(250, Fat.DATA_BLOCK_COUNT);

        assertEquals(Fat.RESERVED, fs.getFat().get(0));
        assertEquals(Fat.RESERVED, fs.getFat().get(2), "根目录块必须标记为保留");
        assertEquals(Fat.RESERVED, fs.getFat().get(255), "末块保留，避免与链结束标志冲突");
    }
}
