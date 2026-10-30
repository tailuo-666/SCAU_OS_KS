package cn.scau.os.fs;

/**
 * 已打开文件表的表项。
 *
 * <p>打开一个文件时，把它的路径、属性、起始块、长度从目录项里"抄"进来，
 * 之后所有读写都用这里的读指针 / 写指针定位，不必每次都重新解析路径。
 * 关闭文件时把长度写回目录项。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：一个"已打开文件"的记录：谁打开了、读写指针在哪、用什么模式。</li>
 *   <li><b>从哪读起</b>：三个模式常量 {@code MODE_READ / MODE_WRITE / MODE_RW}，
 *       再看 {@code readPointer} / {@code writePointer} 两个游标。</li>
 *   <li><b>为什么需要它</b>：同一个文件可以被"读一半"，下次接着读——
 *       指针必须独立于文件本身保存。</li>
 *   <li><b>分工</b>：成员 B 主讲；成员 A 会问"打开表满了怎么办"。</li>
 * </ul>
 */
public class OpenFile {

    /** 读模式。 */
    public static final char MODE_READ = 'r';
    /** 写模式（同时可读）。 */
    public static final char MODE_WRITE = 'w';

    private final String path;
    private final int attribute;
    private int startBlock;
    private int length;
    private final char mode;
    private int readPointer;
    private int writePointer;

    public OpenFile(String path, int attribute, int startBlock, int length, char mode) {
        this.path = path;
        this.attribute = attribute;
        this.startBlock = startBlock;
        this.length = length;
        this.mode = mode;
        this.readPointer = 0;
        this.writePointer = 0;
    }

    public String getPath() {
        return path;
    }

    public int getAttribute() {
        return attribute;
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

    public char getMode() {
        return mode;
    }

    public boolean isWritable() {
        return mode == MODE_WRITE;
    }

    public int getReadPointer() {
        return readPointer;
    }

    public void setReadPointer(int readPointer) {
        this.readPointer = readPointer;
    }

    public int getWritePointer() {
        return writePointer;
    }

    public void setWritePointer(int writePointer) {
        this.writePointer = writePointer;
    }

    /** 读指针是否已到文件末尾。 */
    public boolean isAtEnd() {
        return readPointer >= length;
    }

    @Override
    public String toString() {
        return path + " mode=" + mode + " len=" + length
                + " rp=" + readPointer + " wp=" + writePointer;
    }
}
