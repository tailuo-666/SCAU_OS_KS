package cn.scau.os.mem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储管理模块单元测试：首次适配、分区拆分、回收合并、空间不足、装入与读取。
 */
@DisplayName("存储管理模块")
class MemoryManagerTest {

    private MemoryManager memory;

    @BeforeEach
    void setUp() {
        memory = new MemoryManager();
    }

    @Test
    @DisplayName("用户区 512 字节，初始是一个完整空闲分区")
    void initialLayout() {
        assertEquals(512, MemoryManager.USER_MEMORY_SIZE);
        assertEquals(1, memory.partitionCount());
        assertEquals(512, memory.freeBytes());
        assertEquals(0, memory.usedBytes());
        assertEquals(512, memory.largestFreeBlock());
        assertTrue(memory.partitions().get(0).isFree());
    }

    @Test
    @DisplayName("首次适配：从低地址开始分配并拆分剩余空间")
    void firstFitAllocatesLowestAddress() {
        assertEquals(0, memory.allocate(1, 100));
        assertEquals(100, memory.allocate(2, 100));
        assertEquals(200, memory.allocate(3, 50));

        List<MemoryPartition> parts = memory.partitions();
        assertEquals(4, parts.size(), "三次分配应形成 4 个分区（含尾部空闲区）");
        assertEquals(0, parts.get(0).getStart());
        assertEquals(1, parts.get(0).getOwnerPid());
        assertEquals(100, parts.get(1).getStart());
        assertEquals(2, parts.get(1).getOwnerPid());
        assertEquals(200, parts.get(2).getStart());
        assertEquals(50, parts.get(2).getSize());
        assertEquals(3, parts.get(2).getOwnerPid());
        assertTrue(parts.get(3).isFree());
        assertEquals(250, parts.get(3).getStart());
        assertEquals(262, parts.get(3).getSize());

        assertEquals(250, memory.usedBytes());
        assertEquals(262, memory.freeBytes());
    }

    @Test
    @DisplayName("首次适配会跳过低地址的小空闲区，去用后面的大空闲区")
    void firstFitSkipsTooSmallHole() {
        memory.allocate(1, 100);
        memory.allocate(2, 300);      // 0~99 P1，100~399 P2，400~511 空闲 112
        assertEquals(400, memory.allocate(3, 112), "应使用尾部空闲区");

        memory.release(1);            // 0~99 又空闲，但只有 100 字节
        assertEquals(-1, memory.allocate(4, 120), "100 字节的空闲区装不下 120 字节");
    }

    @Test
    @DisplayName("回收后与相邻空闲区合并，最终恢复为一个整块")
    void releaseMergesNeighbours() {
        memory.allocate(1, 100);
        memory.allocate(2, 100);
        memory.allocate(3, 100);
        assertEquals(4, memory.partitionCount());

        memory.release(2);
        assertEquals(4, memory.partitionCount(), "中间释放不会立即合并");
        assertNull(memory.partitionOf(2));

        memory.release(1);
        assertEquals(3, memory.partitionCount(), "P1 释放后应与 P2 留下的空闲区合并");
        assertEquals(212, memory.largestFreeBlock(), "尾部还留着一块 212 字节的空闲区");

        memory.release(3);
        assertEquals(1, memory.partitionCount());
        assertEquals(512, memory.freeBytes());
        assertEquals(0, memory.usedBytes());
    }

    @Test
    @DisplayName("空间不足返回 -1，且不破坏已有分区")
    void allocateFailsWhenNotEnoughMemory() {
        assertEquals(0, memory.allocate(1, 500));
        assertEquals(-1, memory.allocate(2, 20));
        assertEquals(500, memory.usedBytes());
        assertEquals(12, memory.freeBytes());
        assertEquals(2, memory.partitionCount());
    }

    @Test
    @DisplayName("申请超过用户区大小时返回 -1")
    void allocateTooLarge() {
        assertEquals(-1, memory.allocate(1, 513));
        assertEquals(0, memory.usedBytes());
    }

    @Test
    @DisplayName("同一进程不能重复申请内存")
    void duplicateAllocationRejected() {
        memory.allocate(1, 10);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> memory.allocate(1, 10));
        assertTrue(e.getMessage().contains("重复分配"));
    }

    @Test
    @DisplayName("释放后可被其他进程复用，且地址从低到高")
    void memoryIsReusableAfterRelease() {
        memory.allocate(1, 100);
        memory.release(1);
        assertEquals(0, memory.allocate(2, 64), "释放出的低地址应被优先使用");
        assertNotNull(memory.partitionOf(2));
    }

    @Test
    @DisplayName("装入程序后可按地址逐字节读取，释放时清零")
    void loadProgramAndRead() {
        byte[] code = {10, (byte) 100, (byte) 102};
        int start = memory.allocate(7, code.length);
        memory.loadProgram(start, code);

        for (int i = 0; i < code.length; i++) {
            assertEquals(code[i] & 0xFF, memory.read(start + i));
        }
        assertTrue(memory.partitionOf(7).isLoaded());

        memory.write(start, 42);
        assertEquals(42, memory.read(start));

        memory.release(7);
        assertEquals(0, memory.read(start), "释放后该地址应清零");
        assertNull(memory.partitionOf(7));
    }

    @Test
    @DisplayName("越界读写与越界装入都会被拒绝")
    void addressBoundsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> memory.read(512));
        assertThrows(IllegalArgumentException.class, () -> memory.read(-1));
        assertThrows(IllegalArgumentException.class, () -> memory.write(512, 1));
        assertThrows(IllegalArgumentException.class,
                () -> memory.loadProgram(500, new byte[20]));
    }

    @Test
    @DisplayName("分区快照是拷贝，修改它不影响真实分区表")
    void partitionsSnapshotIsCopy() {
        memory.allocate(1, 100);
        List<MemoryPartition> snapshot = memory.partitions();
        snapshot.get(0).setOwnerPid(99);
        assertEquals(1, memory.partitionOf(1).getOwnerPid(), "快照应不影响原分区");
    }

    @Test
    @DisplayName("reset 之后回到整块空闲")
    void resetRestoresFullMemory() {
        memory.allocate(1, 200);
        memory.allocate(2, 200);
        memory.reset();
        assertEquals(1, memory.partitionCount());
        assertEquals(512, memory.freeBytes());
    }
}
