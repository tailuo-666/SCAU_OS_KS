package cn.scau.os.fs;

import java.nio.charset.StandardCharsets;

/**
 * 目录项：目录树里最小的记录单位，固定 8 字节。
 *
 * <pre>
 *   偏移 0~2   名称    3 字节，1~3 个 ASCII 字母/数字，不足补 0
 *   偏移 3     扩展名  1 字节，'e' 可执行 / 't' 文本 / 0 表示无（目录）
 *   偏移 4     属性    1 字节，见 {@link FileAttribute}
 *   偏移 5     起始盘块 1 字节，0~255
 *   偏移 6~7   文件长度 2 字节，大端序无符号（目录固定写 0）
 * </pre>
 *
 * <p>名称首字节为 0 表示这是一个"空目录项"（已删除或被删除后留下的空洞）。
 * 删除目录项时并不搬移后面的项，只把首字节置 0，这与 MS-DOS 的做法一致，
 * 也使"目录项在块内的位置"可以稳定地作为定位依据。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：目录里的一行记录，只有 8 字节——3 字节文件名 +
 *       1 字节扩展名 + 1 字节属性 + 1 字节起始块 + 2 字节长度。</li>
 *   <li><b>从哪读起</b>：看字段注释 → {@link #encode()} → {@link #decode(byte[], int)}。
 *       编码/解码就是"Java 对象 ↔ 磁盘字节"的翻译。</li>
 *   <li><b>易错点</b>：文件名最多 3 个字符、扩展名最多 1 个，超了会报错或截断；
 *       属性用位掩码表示，一个字节可以同时是"只读 + 可执行"。</li>
 *   <li><b>分工</b>：成员 B 主讲；成员 D 会问"界面上显示的名字后缀为什么是大写"。</li>
 * </ul>
 */
public class DirEntry {

    /** 目录项固定长度。 */
    public static final int SIZE = 8;
    /** 名称字段长度。 */
    public static final int NAME_LENGTH = 3;
    /** 每个磁盘块可容纳的目录项数。 */
    public static final int ENTRIES_PER_BLOCK = Disk.BLOCK_SIZE / SIZE;   // 8

    private String name;
    private char extension;
    private int attribute;
    private int startBlock;
    private int length;

    public DirEntry() {
        this.name = "";
        this.extension = 0;
        this.attribute = FileAttribute.NORMAL;
        this.startBlock = 0;
        this.length = 0;
    }

    public DirEntry(String name, char extension, int attribute, int startBlock, int length) {
        this.name = normalizeName(name);
        this.extension = normalizeExtension(extension);
        this.attribute = attribute;
        this.startBlock = startBlock;
        this.length = length;
    }

    // ------------------------------------------------------------------
    // 编解码
    // ------------------------------------------------------------------

    /**
     * 把本对象写入 buffer 的 offset 处（8 字节）。
     */
    public void encode(byte[] buffer, int offset) {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < NAME_LENGTH; i++) {
            buffer[offset + i] = i < nameBytes.length ? nameBytes[i] : 0;
        }
        buffer[offset + 3] = (byte) (extension == 0 ? 0 : extension);
        buffer[offset + 4] = (byte) attribute;
        buffer[offset + 5] = (byte) startBlock;
        buffer[offset + 6] = (byte) ((length >> 8) & 0xFF);
        buffer[offset + 7] = (byte) (length & 0xFF);
    }

    /** 从 buffer 的 offset 处解出一个目录项。 */
    public static DirEntry decode(byte[] buffer, int offset) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAME_LENGTH; i++) {
            int b = buffer[offset + i] & 0xFF;
            if (b == 0) {
                break;
            }
            sb.append((char) b);
        }
        DirEntry entry = new DirEntry();
        entry.name = sb.toString();
        int ext = buffer[offset + 3] & 0xFF;
        entry.extension = ext == 0 ? 0 : (char) ext;
        entry.attribute = buffer[offset + 4] & 0xFF;
        entry.startBlock = buffer[offset + 5] & 0xFF;
        entry.length = ((buffer[offset + 6] & 0xFF) << 8) | (buffer[offset + 7] & 0xFF);
        return entry;
    }

    /**
     * 根目录的"合成"目录项。
     *
     * <p>根目录没有父目录，也没有对应的 8 字节目录项（它本身就是第 2 块），
     * 但界面上还是要把它当成一个节点显示，因此造一个不落盘的对象。
     * 名称用 {@code \} 而不是 "ROOT"，避免和 3 字符命名规则混淆。
     */
    public static DirEntry rootEntry() {
        DirEntry entry = new DirEntry();
        entry.name = "\\";
        entry.extension = 0;
        entry.attribute = FileAttribute.DIRECTORY;
        entry.startBlock = Fat.ROOT_DIR_BLOCK;
        entry.length = 0;
        return entry;
    }

    /** 该位置是否为空目录项。 */
    public static boolean isEmptySlot(byte[] buffer, int offset) {
        return buffer[offset] == 0;
    }

    /** 把一个位置写成空目录项（全部清 0）。 */
    public static void clearSlot(byte[] buffer, int offset) {
        for (int i = 0; i < SIZE; i++) {
            buffer[offset + i] = 0;
        }
    }

    // ------------------------------------------------------------------
    // 名称规则
    // ------------------------------------------------------------------

    /**
     * 规范化名称：转大写，并校验长度与字符集。
     * 统一用大写存储，这样 "abc.t" 与 "ABC.T" 指向同一个文件。
     */
    public static String normalizeName(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new FsException("文件名不能为空");
        }
        String upper = raw.toUpperCase();
        if (upper.length() > NAME_LENGTH) {
            throw new FsException("文件名 \"" + raw + "\" 过长，最多 " + NAME_LENGTH + " 个字符");
        }
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) {
                throw new FsException("文件名 \"" + raw + "\" 含非法字符，只允许字母和数字");
            }
        }
        return upper;
    }

    /** 扩展名规范化为小写单字符；允许 0 表示无扩展名。 */
    public static char normalizeExtension(char ext) {
        if (ext == 0) {
            return 0;
        }
        char lower = Character.toLowerCase(ext);
        boolean ok = (lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9');
        if (!ok) {
            throw new FsException("扩展名 \"" + ext + "\" 非法，只允许 1 个字母或数字");
        }
        return lower;
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public String getName() {
        return name;
    }

    public char getExtension() {
        return extension;
    }

    public int getAttribute() {
        return attribute;
    }

    public void setAttribute(int attribute) {
        this.attribute = attribute;
    }

    public int getStartBlock() {
        return startBlock;
    }

    public void setStartBlock(int startBlock) {
        this.startBlock = startBlock;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public boolean isDirectory() {
        return FileAttribute.isDirectory(attribute);
    }

    /**
     * 带扩展名的完整名称，例如 "P01.E"、"DOC"。
     *
     * <p>扩展名在磁盘上按小写保存（省去大小写两套判断），但对外显示统一用大写，
     * 与路径规范化的写法保持一致，避免界面上出现 "P01.e" 这种看起来像另一个文件的名称。
     */
    public String getFullName() {
        return extension == 0 ? name : name + "." + Character.toUpperCase(extension);
    }

    /** 文件的块数（目录按 FAT 链长度另算，这里只对文件有意义）。 */
    public int blockCount() {
        return length == 0 ? 0 : (length + Disk.BLOCK_SIZE - 1) / Disk.BLOCK_SIZE;
    }

    public DirEntry copy() {
        return new DirEntry(name, extension, attribute, startBlock, length);
    }

    @Override
    public String toString() {
        return getFullName() + " attr=" + attribute + " start=" + startBlock + " len=" + length;
    }
}
