package cn.scau.os.fs;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件分配表（FAT，File Allocation Table）。
 *
 * <p><b>在模拟操作系统中的角色</b>：FAT 是磁盘空间的唯一"账本"。磁盘有多少块，
 * 它就有多少个表项；每个表项 1 字节，记录"下一块是谁"。既用它判断块是否空闲，
 * 也用它串起显式链接的块链，因此它同时承担了位示图和链指针两种职责。
 *
 * <h2>课程说明中的规格矛盾与本项目的处理</h2>
 * 课程说明要求：FAT 每项 1 字节、共 256 项，因此需要 256 字节 = 4 个物理块；
 * 但同时又要求"第 0、1 块存放 FAT，第 2 块固定为根目录"。这 4 + 1 个块位置
 * 无法同时满足（2 块只有 128 字节，装不下 256 项），所以必须选一个数学上成立的布局。
 *
 * <p>本项目采用可验证的兼容布局：
 * <pre>
 *   块 0      FAT 表项   0 ~  63     (64 项)
 *   块 1      FAT 表项  64 ~ 127     (64 项)
 *   块 2      根目录（固定，8 个目录项）
 *   块 3      FAT 表项 128 ~ 191     (64 项)
 *   块 4      FAT 表项 192 ~ 255     (64 项)
 *   块 5~254  文件 / 子目录数据区    (250 块)
 *   块 255    保留，避免与"255 = 链结束"标志冲突
 * </pre>
 * 这样做的代价是 FAT 被拆成"前 128 项 + 后 128 项"两段，中间被根目录隔开。
 * 对上层没有影响：{@link #get(int)} / {@link #set(int, int)} 把 FAT 当成一个
 * 连续的 256 项数组，物理位置换算集中在 {@link #blockOfEntry(int)} 与
 * {@link #offsetOfEntry(int)} 两个函数里。README 与 docs/FILESYSTEM.md 同样记录了这一点。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：全盘的"账本"。256 个格子，每格 1 字节，记录"我的下一块是谁"。
 *       它一个表同时干了两件事：位示图（是否空闲）+ 链指针（下一块在哪）。</li>
 *   <li><b>从哪读起</b>：{@link #blockOfEntry(int)} → {@link #get(int)} →
 *       {@link #allocateChain(int)} → {@link #chainOf(int)}。
 *       这四步搞懂，文件系统就没有秘密了。</li>
 *   <li><b>关键约定</b>：0 表示空闲，255 表示链结束。所以块 255 必须保留不用，
 *       否则"块号 255"和"链结束 255"分不清。</li>
 *   <li><b>易错点</b>：{@link #RESERVED} 常量值 254 与块号 254 撞车，
 *       判断"是否为系统保留块"必须看<b>块号</b>（{@link #isReservedBlock(int)}），
 *       不能看表项值。这是本项目真实踩过的坑，见 docs/TEST_REPORT.md。</li>
 *   <li><b>分工</b>：成员 B 主讲；成员 A 会追问"FAT 为什么拆成两段"。</li>
 * </ul>
 */
public class Fat {

    /** 0：该块空闲。 */
    public static final int FREE = 0;
    /** 255：文件链的结束标志。 */
    public static final int END_OF_CHAIN = 255;
    /** 254：系统保留块（FAT 自身、根目录、末块）。 */
    public static final int RESERVED = 254;

    /** FAT 表项总数 = 磁盘块数。 */
    public static final int ENTRY_COUNT = Disk.BLOCK_COUNT;

    /** 第 0、1 块：FAT 前 128 项。 */
    public static final int FAT_BLOCK_HEAD = 0;
    /** 第 2 块：根目录，固定。 */
    public static final int ROOT_DIR_BLOCK = 2;
    /** 第 3、4 块：FAT 后 128 项。 */
    public static final int FAT_BLOCK_TAIL = 3;
    /** 数据区起始块。 */
    public static final int FIRST_DATA_BLOCK = 5;
    /** 数据区结束块（含）。 */
    public static final int LAST_DATA_BLOCK = 254;
    /** 数据区总块数。 */
    public static final int DATA_BLOCK_COUNT = LAST_DATA_BLOCK - FIRST_DATA_BLOCK + 1;

    /** 每块能装下的 FAT 表项数（64 字节 / 每项 1 字节）。 */
    public static final int ENTRIES_PER_BLOCK = Disk.BLOCK_SIZE;

    /**
     * 被系统占用的物理块：0、1（FAT 前段）、2（根目录）、3、4（FAT 后段）、255（保留）。
     * 它们对应的 FAT 表项一律写 {@link #RESERVED}。
     */
    private static final int[] RESERVED_BLOCKS = {0, 1, 2, 3, 4, 255};

    /**
     * 该块是否属于系统保留区（FAT 自身、根目录、末块）。
     *
     * <p><b>为什么必须用块号判断，而不是判断表项值</b>：FAT 表项只有 1 字节，
     * 取值范围 0~255，而块号也是 0~255，因此任何用作标记的数值都必然与某个
     * 合法块号重合。这里 {@link #RESERVED} 取 254，恰好和最后一个数据块 254 撞号。
     * 如果沿着链走的时候用"下一块 == 254 就当保留块"来判断，长度足够大的文件一旦
     * 跨到第 254 块就会被误判成坏链而截断——测试里写满整个磁盘时就会触发。
     * 所以链遍历只看"是否落在数据区范围内"，保留块只按块号判断。
     */
    public static boolean isReservedBlock(int blockNo) {
        for (int reserved : RESERVED_BLOCKS) {
            if (reserved == blockNo) {
                return true;
            }
        }
        return false;
    }

    private final Disk disk;
    /** 内存中的 FAT 副本，是运行期的唯一真值；每次修改都会写回磁盘。 */
    private final int[] entries = new int[ENTRY_COUNT];

    public Fat(Disk disk) {
        this.disk = disk;
    }

    // ------------------------------------------------------------------
    // 表项 <-> 物理位置 的映射（FAT 被拆成两段的补偿逻辑）
    // ------------------------------------------------------------------

    /** FAT 表项 index 存放在哪个物理块里。 */
    public static int blockOfEntry(int index) {
        if (index < 2 * ENTRIES_PER_BLOCK) {          // 0 ~ 127
            return FAT_BLOCK_HEAD + index / ENTRIES_PER_BLOCK;      // 块 0、1
        }
        return FAT_BLOCK_TAIL + (index - 2 * ENTRIES_PER_BLOCK) / ENTRIES_PER_BLOCK; // 块 3、4
    }

    /** FAT 表项 index 在所在物理块内的字节偏移（0 ~ 63）。 */
    public static int offsetOfEntry(int index) {
        if (index < 2 * ENTRIES_PER_BLOCK) {
            return index % ENTRIES_PER_BLOCK;
        }
        return (index - 2 * ENTRIES_PER_BLOCK) % ENTRIES_PER_BLOCK;
    }

    /** 该物理块是否是 FAT 自己的存放块。 */
    public static boolean isFatBlock(int blockNo) {
        return blockNo == 0 || blockNo == 1 || blockNo == 3 || blockNo == 4;
    }

    // ------------------------------------------------------------------
    // 加载 / 保存
    // ------------------------------------------------------------------

    /** 从磁盘读出 4 个 FAT 块，还原成 256 项数组。 */
    public void load() {
        for (int i = 0; i < ENTRY_COUNT; i++) {
            byte[] block = disk.readBlock(blockOfEntry(i));
            entries[i] = block[offsetOfEntry(i)] & 0xFF;
        }
    }

    /** 把 256 项数组写回 4 个 FAT 块。 */
    public void flush() {
        for (int b = 0; b < 4; b++) {
            disk.clearBlock(blockOfEntry(b * ENTRIES_PER_BLOCK));
        }
        for (int i = 0; i < ENTRY_COUNT; i++) {
            int blockNo = blockOfEntry(i);
            byte[] block = disk.readBlock(blockNo);
            block[offsetOfEntry(i)] = (byte) entries[i];
            disk.writeBlock(blockNo, block);
        }
    }

    /** 是否已经格式化过（用保留标记判断）。 */
    public boolean isFormatted() {
        return entries[0] == RESERVED
                && entries[1] == RESERVED
                && entries[2] == RESERVED
                && entries[3] == RESERVED
                && entries[4] == RESERVED
                && entries[255] == RESERVED;
    }

    /**
     * 建立空的 FAT：所有数据块空闲，FAT 自身、根目录块和末块标记为保留。
     */
    public void reset() {
        for (int i = 0; i < ENTRY_COUNT; i++) {
            entries[i] = FREE;
        }
        for (int block : RESERVED_BLOCKS) {
            entries[block] = RESERVED;
        }
        flush();
    }

    // ------------------------------------------------------------------
    // 基本读写
    // ------------------------------------------------------------------

    public int get(int blockNo) {
        return entries[blockNo];
    }

    public void set(int blockNo, int value) {
        entries[blockNo] = value & 0xFF;
    }

    public boolean isFree(int blockNo) {
        return entries[blockNo] == FREE;
    }

    // ------------------------------------------------------------------
    // 分配 / 回收（显式链接）
    // ------------------------------------------------------------------

    /**
     * 申请一段块链。
     *
     * <p>做法：从数据区低地址向高地址扫描出 count 个空闲块，然后把它们按顺序串起来，
     * 最后一块写入 {@link #END_OF_CHAIN}。返回链首块号；空间不足返回 -1。
     *
     * @param count 需要的块数
     */
    public int allocateChain(int count) {
        if (count <= 0) {
            throw new FsException("申请磁盘块数必须大于 0");
        }
        // 第 1 步：从数据区（5~254）里"只看不占"地挑出 count 个空闲块。
        // 先挑后占，是为了避免挑到一半发现不够、结果留下半条坏链。
        List<Integer> freeBlocks = new ArrayList<>(count);
        for (int block = FIRST_DATA_BLOCK; block <= LAST_DATA_BLOCK && freeBlocks.size() < count; block++) {
            if (entries[block] == FREE) {
                freeBlocks.add(block);
            }
        }
        if (freeBlocks.size() < count) {
            return -1;               // 磁盘空间不足，调用方负责报错
        }
        // 第 2 步：把挑出来的块串成一条链——每个格子里写"下一块"的块号，
        // 最后一格写 255（链结束）。这就是"显式链接"四个字的全部含义。
        for (int i = 0; i < count; i++) {
            int current = freeBlocks.get(i);
            entries[current] = (i == count - 1) ? END_OF_CHAIN : freeBlocks.get(i + 1);
        }
        flush();                     // 立刻落盘：此刻断电，磁盘上也是一条完整的链
        return freeBlocks.get(0);    // 返回链首块号，上层把它当作"文件的身份证"
    }

    /** 释放整条块链，把链上每个块重新标记为空闲。 */
    public void freeChain(int startBlock) {
        if (startBlock == FREE || isReservedBlock(startBlock)) {
            return;
        }
        int block = startBlock;
        int guard = 0;               // 计数器：万一 FAT 里存在环，靠它跳出死循环
        while (block != END_OF_CHAIN && block >= FIRST_DATA_BLOCK && block <= LAST_DATA_BLOCK) {
            int next = entries[block];   // 先记住下一块是谁，因为马上要把这一格清成 0
            entries[block] = FREE;
            block = next;
            if (++guard > ENTRY_COUNT) {
                throw new FsException("FAT 链出现环，释放中止（起始块 " + startBlock + "）");
            }
        }
        flush();
    }

    /**
     * 把一条块链展开成块号列表（首块在前）。遇到坏链时提前结束，
     * 不会因为一个损坏的表项就死循环。
     *
     * <p>注意这里只把 0（空闲）当作链结束，<b>不</b>把表项值 254 当成"保留块"：
     * 254 也是一个合法块号，详见 {@link #isReservedBlock(int)} 的说明。
     */
    public List<Integer> chainOf(int startBlock) {
        List<Integer> chain = new ArrayList<>();
        int block = startBlock;
        int guard = 0;
        while (block >= FIRST_DATA_BLOCK && block <= LAST_DATA_BLOCK && guard++ <= ENTRY_COUNT) {
            chain.add(block);
            int next = entries[block];
            if (next == END_OF_CHAIN || next == FREE) {
                break;
            }
            if (next < FIRST_DATA_BLOCK || next > LAST_DATA_BLOCK) {
                break;              // 坏链：指向了保留区或越界，提前收尾
            }
            block = next;
        }
        return chain;
    }

    /** 在链尾追加一个新块，返回新块号；没有空间返回 -1。 */
    public int appendBlock(int startBlock) {
        int newBlock = allocateChain(1);
        if (newBlock < 0) {
            return -1;
        }
        int tail = startBlock;
        int guard = 0;
        while (entries[tail] != END_OF_CHAIN && guard++ <= ENTRY_COUNT) {
            int next = entries[tail];
            if (next < FIRST_DATA_BLOCK || next > LAST_DATA_BLOCK) {
                break;              // 坏链：就地收尾，避免把新块挂到保留区
            }
            tail = next;
        }
        entries[tail] = newBlock;
        entries[newBlock] = END_OF_CHAIN;
        flush();
        return newBlock;
    }

    /** 链长（块数）。 */
    public int chainLength(int startBlock) {
        return chainOf(startBlock).size();
    }

    /** 空闲块数量。 */
    public int freeBlockCount() {
        int free = 0;
        for (int block = FIRST_DATA_BLOCK; block <= LAST_DATA_BLOCK; block++) {
            if (entries[block] == FREE) {
                free++;
            }
        }
        return free;
    }

    /** 已分配的数据块数量。 */
    public int usedBlockCount() {
        return DATA_BLOCK_COUNT - freeBlockCount();
    }

    /** 导出 256 项副本，供界面展示。 */
    public int[] snapshot() {
        int[] copy = new int[ENTRY_COUNT];
        System.arraycopy(entries, 0, copy, 0, ENTRY_COUNT);
        return copy;
    }

    /** 该块属于哪一类（给磁盘网格上色用）。 */
    public BlockKind kindOf(int blockNo) {
        if (isFatBlock(blockNo)) {
            return BlockKind.FAT;
        }
        if (blockNo == ROOT_DIR_BLOCK) {
            return BlockKind.ROOT_DIR;
        }
        if (isReservedBlock(blockNo)) {
            return BlockKind.RESERVED;
        }
        return entries[blockNo] == FREE ? BlockKind.FREE : BlockKind.USED;
    }

    /** 磁盘块类型。 */
    public enum BlockKind {
        FAT("FAT 区"),
        ROOT_DIR("根目录"),
        RESERVED("保留块"),
        FREE("空闲"),
        USED("已分配");

        private final String label;

        BlockKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
