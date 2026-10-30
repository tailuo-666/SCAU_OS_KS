# 文件管理实现细节

本文讲磁盘、FAT、目录项、路径解析、文件操作、打开文件表与持久化。
所有数字都可以用 `fdisk`、`dir` 命令或块网格界面现场核对。

---

## 1. 磁盘

```java
public static final int BLOCK_SIZE  = 64;      // 每块 64 字节
public static final int BLOCK_COUNT = 256;     // 共 256 块
public static final int TOTAL_BYTES = 16384;   // 16 KB
```

`Disk` 用 `RandomAccessFile` 直接读写 `disk.img`：

```java
byte[] buffer = disk.readBlock(blockNo);   // 64 字节
disk.writeBlock(blockNo, buffer);          // 写回并 flush
```

- 镜像文件不存在 → 创建并填 16384 个 0；
- 文件长度不是 16384 → 重建（这是一次"格式化"级别的破坏，只在文件损坏时发生）；
- `readBlock` / `writeBlock` 有块号范围检查和 `synchronized` 保护。

**为什么真的用文件**：课程要求"用一个文件模拟磁盘"，且要求重启后文件系统还在。
用内存数组虽然简单，但进程一退出数据就没了，也就没法演示持久化。

---

## 2. 块分配表（FAT）

### 2.1 为什么 FAT 占 4 个块

课程说明同时要求：

- FAT 共 256 项、每项 1 字节 ⇒ 需要 256 字节；
- 第 0、1 块放 FAT，第 2 块固定为根目录 ⇒ 只有 128 字节可用。

两者矛盾。本项目的处理：**保留"根目录在第 2 块"和"FAT 256 项"，
把 FAT 拆成前后两段**：

| 物理块 | 存放的 FAT 表项 |
|---|---|
| 0 | 0 ~ 63 |
| 1 | 64 ~ 127 |
| 2 | （根目录，不是 FAT） |
| 3 | 128 ~ 191 |
| 4 | 192 ~ 255 |
| 5 ~ 254 | 数据区（250 块） |
| 255 | 保留（避免和"255 = 链结束"冲突） |

换算集中在两个函数：

```java
Fat.blockOfEntry(127) == 1     // 表项 127 在第 1 块
Fat.blockOfEntry(128) == 3     // 表项 128 在第 3 块
Fat.offsetOfEntry(128) == 0
```

上层（`FileSystem`）永远把 FAT 当成 `int[256]` 用，看不到"两段"这件事。

### 2.2 三种特殊表项值

| 值 | 含义 | 说明 |
|---|---|---|
| 0 | 空闲 | 兼作"位示图"：统计 5~254 中值为 0 的块数就是空闲块数 |
| 255 | 链结束 | 文件/目录最后一块 |
| 254 | 保留块标记 | **只对块 0、1、2、3、4、255 的表项有意义** |

**这里有一个必须讲清楚的坑**：254 同时是"最后一个数据块的块号"。
如果沿链遍历时判断"下一块 == 254 就认为是保留块"，那么一个写满磁盘的文件
在第 254 块就会被判为坏链而截断（读出来少 64 字节，且释放时漏掉该块）。

修复方式：链遍历只看"下一块是否落在 5~254 的数据区范围内"，
保留块用 `Fat.isReservedBlock(blockNo)` **按块号**判断。
回归测试：`FileSystemTest.chainCrossingBlock254IsNotTruncated`。

### 2.3 分配与释放

```java
int start = fat.allocateChain(3);   // 从低地址找 3 个空闲块，串成链
List<Integer> chain = fat.chainOf(start);   // [start, next, ..., 最后一块]
fat.appendBlock(start);            // 链尾追加一块（打开文件写入时用）
fat.freeChain(start);              // 整条链标记为空闲
```

`allocateChain` 的"首次适配"体现在：扫描总是从第 5 块开始，取到够用的前 n 个空闲块就停，
所以新文件通常落在低地址（默认文件树里 P01 在第 10 块、P02 在第 11 块……）。

`chainOf` 有三重保护：块号范围检查、`guard` 计数、坏链提前收尾，
即使 FAT 被人为改坏也不会死循环。

---

## 3. 目录项

8 字节固定长度：

| 偏移 | 长度 | 字段 | 说明 |
|---|---|---|---|
| 0 | 3 | 名称 | 大写字母/数字，不足 3 位用 0 补齐 |
| 3 | 1 | 扩展名 | 1 个字符，0 表示无扩展名（目录就是 0） |
| 4 | 1 | 属性 | 位标志 |
| 5 | 1 | 起始块 | 文件的第一块 / 子目录的第一块 |
| 6 | 2 | 长度 | 字节数（目录为 0） |

属性位：

| 位 | 名称 | 含义 |
|---|---|---|
| 0x01 | READ_ONLY | 只读：不可写、不可删、不可移动 |
| 0x02 | SYSTEM | 系统文件（仅标记） |
| 0x04 | DIRECTORY | 目录 |
| 0x08 | EXECUTABLE | 可执行（`run` 只认这个） |

每块 64 字节 ⇒ **每块 8 个目录项**。

- **根目录固定第 2 块，不能扩展**：最多 8 项。默认文件树用了 7 项，
  再建第 9 项会报"根目录最多 8 个目录项，已满"。
- **子目录可扩展**：`\BIN` 有 10 个文件 ⇒ 占 2 块（16 项容量）。
  扩容发生在 `addEntry` 找不到空槽时：申请新块、接到目录链尾、重写目录项。

删除目录项不搬动数据：把名称首字节置 0（`DirEntry.clearSlot`），
后续查找会跳过该槽位。

---

## 4. 路径解析

```java
PathUtil.normalize("doc/n01.t")   → "\DOC\N01.T"
PathUtil.resolve("\\DOC", "N01.T")→ "\DOC\N01.T"
PathUtil.parent("\\DOC\\N01.T")   → "\DOC"
PathUtil.splitName("P01.E")       → {name="P01", extension='e'}
```

`FileSystem.lookup(path)` 逐段查找：

```
\DOC\SUB\A.T
 ├─ 从根目录块 2 开始
 ├─ 找 "DOC" → 是目录 → 取它的起始块
 ├─ 在该块链里找 "SUB" → 是目录 → 取起始块
 └─ 找 "A.T" → 命中，返回 (所在目录块, 槽位下标, 目录项副本)
```

路径规则：

- `\` 与 `/` 等价，全部转大写，连续分隔符折叠；
- 相对路径以 `Shell.currentDir` 为基准（`chdir` 之后生效）；
- 大小写不敏感（存储时名称大写、扩展名小写，比较时两边都规范化）；
- 名称最多 3 字符、扩展名 1 字符——这是 8 字节目录项的硬限制，
  所以默认文件叫 `P01.E`、`HLP.T`、`RED.T`，不是 `P01.EXE`、`HELP.TXT`。

---

## 5. 文件操作

| 操作 | 做法 | 失败时 |
|---|---|---|
| `createFile` | 先按内容分配新链并写块，再写目录项 | 目录项写不进去 → `freeChain` 回滚，不泄漏块 |
| `readFile` | `readChain(start, length)`：沿链读，按 `length` 截断 | 文件不存在/是目录 → `FsException` |
| `writeFile` | **先分配新链写完，再释放旧链** | 磁盘满时原文件完好 |
| `deleteFile` | 先 `freeChain` 再删目录项 | 被打开 / 只读 → 拒绝 |
| `copyFile` | 读源内容 → 在目标位置 `writeNewChain` | 目标目录不存在 → 报错 |
| `moveFile` | 同一目录：只改目录项名字（不搬块）；跨目录：复制 + 删除 | 只读 / 被打开 / 目标重名 → 拒绝 |
| `mkdir` | 分配 1 块作为空目录，写入父目录 | 根目录满 / 无空间 → 报错 |
| `rmdir` | 检查目录为空 → 释放块 + 删目录项 | 非空 → "目录非空" |
| `deldir` | 递归删除子项后再删自己 | — |

### 跨块读写

一个文件 `length = 294` 字节：

```
blocks = ceil(294 / 64) = 5
```

写入时逐块填 64 字节，最后一块只填 `294 - 4×64 = 38` 字节；
读取时按同样的算式反向拼回。`DOC\N01.T` 就是这样一个 5 块文件，
`FileSystemTest.crossBlockFileRoundTrip` 断言了 5 块、294 字节、首尾内容一致。

### 目录跨块

`\BIN` 有 10 个目录项：

```
addEntry: 第 1~8 项写在第 1 块
          第 9 项找不到空槽 → 申请第 2 块 → 接到目录链尾 → 写入
```

`directoryBlockCount("\BIN") == 2`，`directoryCapacity("\BIN") == 16`。

---

## 6. 已打开文件表

```java
OpenFile openFile = fs.openFile("\\DOC\\N01.T", OpenFile.MODE_READ);
byte[] part = fs.readOpenedFile("\\DOC\\N01.T", 30);   // 读指针前移 30
fs.closeFile("\\DOC\\N01.T");                          // 长度写回目录项
```

- 最多 **5** 项，第 6 个报"已打开文件表已满"；
- 表项记录路径、属性、起始块、长度、读写指针、模式（`r` / `w`）；
- 文件被打开时**不能删除、不能被 `writeFile` 覆盖**（避免"改了目录项但表项还指着旧链"）；
- `writeOpenedFile` 从写指针处写，必要时 `appendBlock` 扩展链；
- `closeFile` 把表项里的起始块与长度写回目录项。

命令层的 `open / read / close` 就是这三个方法的薄封装。

---

## 7. 持久化

所有修改都会立刻落到镜像文件：

```
Fat.allocateChain / freeChain / appendBlock  →  Fat.flush()  →  disk.writeBlock() ×4 + flush
DirEntry 写入                                 →  writeEntry() →  disk.writeBlock(目录块)
文件内容                                      →  disk.writeBlock(数据块)
```

重启时：

```java
Disk disk = new Disk(imagePath);
Fat fat = new Fat(disk);
fat.load();                       // 从 4 个 FAT 块还原 256 项
if (!fat.isFormatted()) {         // 块 0~4、255 不是保留标记 → 认为没格式化
    fs.format();
    fs.installDefaultTree();
}
```

`isFormatted()` 的判断依据是"块 0、1、2、3、4、255 的表项都是 254"。
这条判断也解释了为什么保留块必须在 FAT 里留下痕迹——它是"格式化过了"的标记。

`IntegratedScenarioTest.persistenceAcrossSystemRestart` 关闭系统后重新打开，
断言文件内容、子目录、`\BIN` 下 10 个程序都还在。

---

## 8. 默认文件树

首次格式化写入（`DefaultFileTree.install`，全部通过 `FileSystem` 公开方法创建）：

```
\                       7 项（5 目录 + 2 文件），容量 8
├─ BIN\                 10 个可执行文件，占 2 块（P01~P10）
├─ DOC\                 7 个文本文件（N01 294 字节 / 5 块，N06 只读）
├─ DAT\                 3 个数据文件
├─ SYS\                 3 个系统文件（带 SYSTEM 属性，CFG 还是只读）
├─ TMP\                 空目录（留给实验）
├─ HLP.T                命令速查
└─ RED.T                课程设计说明
```

初始占用：**44 块已用 / 206 块空闲**（数据区共 250 块）。
这个数字可以用 `fdisk` 命令核对；创建/删除文件后 `已用 + 空闲` 始终等于 250。

---

## 9. 块归属的可视化

`FileSystem.blockOwners()` 不额外存储映射，而是**从目录树 + FAT 链推导**：

```java
owners[0] = "<FAT 区>";  owners[2] = "<根目录>";  owners[255] = "<系统保留>";
markOwners(2, "\\", owners, 0);   // 递归：目录链上的块 → 目录路径；文件链 → 文件路径
```

磁盘网格界面右键/点击某个块，显示的就是 `owners[block]` 与 `fat[block]`：

```
块 154    FAT=155    已分配    \DOC\N01.T    下一块 → 155
```

这样"文件到底占哪几块"是可见、可验证的，答辩时可以直接指着界面讲。

---

## 10. 限制与取舍

| 限制 | 原因 | 想扩展怎么做 |
|---|---|---|
| 文件名 3 字符 | 8 字节目录项（3+1+1+1+2） | 改成 16 或 32 字节目录项，同步改 `DirEntry.SIZE` 与 `ENTRIES_PER_BLOCK` |
| 根目录不能扩展 | 课程要求固定第 2 块 | 把根目录也做成 FAT 链，`directoryChain` 特判可以删掉 |
| 单级属性、无时间戳 | 目录项只有 8 字节 | 加字段，或单独存一个属性块 |
| 最大文件 65535 字节 | 长度字段 2 字节 | 改成 4 字节（目录项变 10 字节，每块 6 项） |
| 不支持硬链接/符号链接 | 课程未要求 | 目录项加"链接计数"或用特殊属性位 |
| 目录不排序存储 | 简单；`list()` 输出时才排序 | 插入时按名称有序，便于二分查找 |
