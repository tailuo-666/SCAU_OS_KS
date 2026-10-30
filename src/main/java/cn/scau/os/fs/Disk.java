package cn.scau.os.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模拟磁盘。
 *
 * <p><b>在模拟操作系统中的角色</b>：它是整个文件系统的"硬件"。磁盘被建成一个
 * 本地二进制文件 {@code disk.img}，容量固定为 256 块 × 64 字节 = 16384 字节。
 * 上层的 FAT、目录、文件内容全部以"块"为单位读写这个文件，
 * 没有任何数据是只存在于内存中的假象。
 *
 * <p><b>为什么不用内存数组代替</b>：课程设计要求"用一个文件 disk 模拟磁盘"，
 * 并且要求程序重启后能恢复原有文件系统。用 RandomAccessFile 直接定位读写，
 * 进程退出后数据仍在磁盘镜像里，重启后 {@link Fat#load()} 即可还原。
 *
 * <p>本类只负责"按块读写"这一件事，不理解 FAT，也不理解目录结构。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：磁盘的"物理层"。它只会做两件事——读一块、写一块，
 *       完全不懂文件、目录、FAT。相当于真实硬盘的驱动程序。</li>
 *   <li><b>从哪读起</b>：先看常量 {@code BLOCK_SIZE} / {@code BLOCK_COUNT}，
 *       再看 {@link #readBlock(int)} 与 {@link #writeBlock(int, byte[])}，
 *       这两个方法就是整个文件系统的地基。</li>
 *   <li><b>为什么用文件</b>：数据要能"关机再开机还在"，所以用
 *       {@code RandomAccessFile} 把 16384 字节写进 {@code disk.img}。</li>
 *   <li><b>易错点</b>：块号从 0 开始，块 N 的字节偏移是 {@code N * 64}；
 *       越界要抛异常而不是默默截断。</li>
 *   <li><b>分工</b>：成员 B 主讲；成员 A 会问"块大小为什么是 64"。</li>
 * </ul>
 */
public class Disk implements AutoCloseable {

    /** 每个物理块 64 字节。 */
    public static final int BLOCK_SIZE = 64;
    /** 磁盘共 256 块。 */
    public static final int BLOCK_COUNT = 256;
    /** 磁盘总容量 16384 字节。 */
    public static final int TOTAL_BYTES = BLOCK_SIZE * BLOCK_COUNT;

    private final Path imagePath;
    private final RandomAccessFile file;

    /**
     * 打开（不存在则创建）磁盘镜像文件。
     * 若文件大小与规格不符，会重新调整为 16384 字节的全零文件。
     */
    public Disk(Path imagePath) {
        this.imagePath = imagePath.toAbsolutePath();
        try {
            Path parent = this.imagePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            this.file = new RandomAccessFile(this.imagePath.toFile(), "rw");
            if (this.file.length() != TOTAL_BYTES) {
                resizeToSpec();
            }
        } catch (IOException e) {
            throw new FsException("无法打开磁盘镜像：" + this.imagePath + "（" + e.getMessage() + "）", e);
        }
    }

    /** 把镜像文件重置为规定容量，全部字节清零。 */
    private void resizeToSpec() throws IOException {
        file.setLength(0);
        file.seek(0);
        file.write(new byte[TOTAL_BYTES]);
        file.getFD().sync();
    }

    /**
     * 读取一个物理块，返回长度为 {@link #BLOCK_SIZE} 的字节数组。
     * 每次调用都是一次真实的磁盘读操作。
     */
    public synchronized byte[] readBlock(int blockNo) {
        checkBlockNo(blockNo);
        byte[] buffer = new byte[BLOCK_SIZE];
        try {
            file.seek((long) blockNo * BLOCK_SIZE);
            file.readFully(buffer);
            return buffer;
        } catch (IOException e) {
            throw new FsException("读取磁盘块 " + blockNo + " 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 写入一个物理块。data 不足 64 字节时用 0 补齐，超出的部分被忽略。
     */
    public synchronized void writeBlock(int blockNo, byte[] data) {
        checkBlockNo(blockNo);
        byte[] buffer = new byte[BLOCK_SIZE];
        if (data != null) {
            System.arraycopy(data, 0, buffer, 0, Math.min(data.length, BLOCK_SIZE));
        }
        try {
            file.seek((long) blockNo * BLOCK_SIZE);
            file.write(buffer);
        } catch (IOException e) {
            throw new FsException("写入磁盘块 " + blockNo + " 失败：" + e.getMessage(), e);
        }
    }

    /** 把指定块写满 0。格式化与"清空目录块"都会用到。 */
    public void clearBlock(int blockNo) {
        writeBlock(blockNo, new byte[BLOCK_SIZE]);
    }

    /** 全盘清零，等同于低级格式化。 */
    public synchronized void clear() {
        try {
            file.seek(0);
            file.write(new byte[TOTAL_BYTES]);
            file.getFD().sync();
        } catch (IOException e) {
            throw new FsException("清空磁盘失败：" + e.getMessage(), e);
        }
    }

    /** 把缓冲区内容真正刷到操作系统磁盘（便于测试与断电模拟）。 */
    public synchronized void flush() {
        try {
            file.getFD().sync();
        } catch (IOException e) {
            throw new FsException("刷新磁盘失败：" + e.getMessage(), e);
        }
    }

    public Path getImagePath() {
        return imagePath;
    }

    /** 磁盘镜像当前的真实文件大小（字节）。 */
    public long imageSize() {
        try {
            return file.length();
        } catch (IOException e) {
            throw new FsException("无法获取磁盘镜像大小：" + e.getMessage(), e);
        }
    }

    /** 磁盘镜像是否有内容（用来判断"是否首次启动"）。 */
    public boolean isEmptyImage() {
        for (int block = 0; block < BLOCK_COUNT; block++) {
            byte[] data = readBlock(block);
            for (byte b : data) {
                if (b != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private void checkBlockNo(int blockNo) {
        if (blockNo < 0 || blockNo >= BLOCK_COUNT) {
            throw new FsException("非法的磁盘块号：" + blockNo + "（有效范围 0~" + (BLOCK_COUNT - 1) + "）");
        }
    }

    @Override
    public synchronized void close() {
        try {
            file.close();
        } catch (IOException e) {
            // 关闭失败不影响业务结果，但必须让使用者看到
            throw new FsException("关闭磁盘镜像失败：" + e.getMessage(), e);
        }
    }

    /** 便于日志输出：磁盘镜像的短路径。 */
    @Override
    public String toString() {
        return "Disk[" + imagePath.getFileName() + ", " + TOTAL_BYTES + " bytes]";
    }

    /** 给测试/工具类用：把镜像文件删除（模拟"换一张新盘"）。 */
    public static void deleteImage(Path imagePath) {
        File f = imagePath.toFile();
        if (f.exists() && !f.delete()) {
            throw new FsException("无法删除磁盘镜像：" + imagePath);
        }
    }
}
