# 代码导读（逐类）

目标：**用最短路径读懂 8700 行代码**。下面给出一条 6 个文件的阅读主线，
再按包逐个说明每个类的职责与关键方法，最后给两条完整调用链。

---

## 1. 先读这 6 个文件（约 1.5 小时）

| 顺序 | 文件 | 为什么先读它 | 重点看 |
|---|---|---|---|
| 1 | `fs/Disk.java` | 最底层，只有"按块读写一个文件" | `BLOCK_SIZE=64`、`BLOCK_COUNT=256`、`readBlock/writeBlock` |
| 2 | `fs/Fat.java` | 磁盘空间账本，也是最有"坑"的地方 | 两段式布局 `blockOfEntry`、`allocateChain`、`chainOf` |
| 3 | `fs/FileSystem.java` | 文件管理门面，所有文件操作都在这里 | `lookup`、`createFile`、`readFile/writeFile`、`openFile` |
| 4 | `engine/OperatingSystem.java` | 内核，**读 `tickInternal()` 就懂了整个系统** | 7 步时钟、`executeOneInstruction`、`handleDeviceCountdown` |
| 5 | `proc/ProcessManager.java` | 进程状态机 | `createProcess`、`destroy`、`block/wake`、`dispatch` |
| 6 | `ui/MainFrame.java` | 界面怎么把上面这些组装起来 | `refresh()`、`SimulationDriver` |

读完这 6 个，剩下的类都是"查漏补缺"。

---

## 2. fs 包：文件管理

### `Disk`（189 行）
模拟磁盘的"硬件"。底层是 `RandomAccessFile`，文件固定 16384 字节。

```java
Disk disk = new Disk(Path.of("disk.img"));
byte[] block = disk.readBlock(42);   // 读第 42 块（64 字节）
disk.writeBlock(42, block);          // 写回
```

关键点：文件长度不等于规格时会自动重建为零文件；`readBlock/writeBlock` 有块号检查。

### `Fat`（358 行）
256 项的一字节 FAT，兼任"位示图"（0 = 空闲）。

| 方法 | 作用 |
|---|---|
| `blockOfEntry(i)` / `offsetOfEntry(i)` | 第 i 项在哪个物理块、块内偏移（**两段式布局的唯一换算点**） |
| `load()` / `flush()` | 从 4 个 FAT 块读入 / 写回 |
| `isFormatted()` | 检查块 0~4、255 是否都是保留标记 |
| `allocateChain(n)` | 从低地址找 n 个空闲块，串成链，返回首块；空间不足返回 -1 |
| `chainOf(start)` | 把链展开成块号列表（带环保护、坏链保护） |
| `appendBlock(start)` | 在链尾追加一块（打开文件写入时用） |
| `freeChain(start)` | 释放整条链 |
| `isReservedBlock(b)` | **按块号**判断保留块（不能按表项值判断，见 README 第 3.1 节） |
| `kindOf(b)` | 给界面用：FAT / 根目录 / 保留 / 空闲 / 已用 |

### `DirEntry`（231 行）
8 字节目录项的编解码。

```java
DirEntry e = new DirEntry("P01", 'e', FileAttribute.EXECUTABLE, 10, 2);
byte[] buf = new byte[64];
e.encode(buf, 3 * 8);                  // 写到第 3 个槽位
DirEntry back = DirEntry.decode(buf, 3 * 8);
```

`normalizeName` 校验"最多 3 个字符、只能是字母数字并转大写"，
`normalizeExtension` 转小写存储，`getFullName()` 显示时统一大写。

### `PathUtil`（155 行）
纯函数工具：

- `normalize("doc/n01.t")` → `\DOC\N01.T`
- `resolve("\\DOC", "N01.T")` → `\DOC\N01.T`（相对路径）
- `parent` / `fileName` / `join` / `segments` / `splitName`

### `FileSystem`（936 行）
门面，上层只认它。内部用 `lookup()` 把路径拆成段逐级查找目录项。

| 分组 | 方法 |
|---|---|
| 打开/格式化 | `open`、`createFresh`、`format`、`installDefaultTree` |
| 查询 | `exists`、`isDirectory`、`stat`、`list`、`freeBlocks`、`usedBlocks` |
| 目录 | `mkdir`、`rmdir`、`deldir`、`directoryBlockCount` |
| 文件 | `createFile`、`readFile/readText`、`writeFile/writeText`、`deleteFile`、`copyFile`、`moveFile`、`changeAttribute` |
| 打开文件 | `openFile`、`closeFile`、`readOpenedFile`、`writeOpenedFile` |
| 界面数据 | `buildTree`、`fatSnapshot`、`blockOwners`、`ownerOfBlock` |

两个容易忽略的细节：

1. `directoryChain(int start)` 对根目录特判（根目录在第 2 块，不在数据区 5~254，
   直接 `fat.chainOf(2)` 会得到空链）。
2. `writeFile` 先写新链、再释放旧链，所以"磁盘满导致写入失败"时原文件完好。

### `DefaultFileTree`（161 行）
首次格式化时写入的 5 个目录、25 个文件、10 个可执行程序。
它只调用 `FileSystem` 的公开方法，因此本身就是一次完整的文件系统演练。

### `OpenFile` / `OpenFileTable` / `FileAttribute` / `DirNode` / `FsException`
小类：打开文件表项（读写指针）、最多 5 项的表、属性位工具、目录树只读节点、业务异常。

---

## 3. exec 包：CPU 与指令

### `InstructionCodec`（312 行）

```java
byte[] code = InstructionCodec.assemble("x=10\nx++\n!A3\nend");  // {10,100,106,102}
List<String> text = InstructionCodec.disassemble(code);
InstructionCodec.Decoded d = InstructionCodec.decode(106);       // DEVICE, 'A', 3
```

编码规则：赋值 0~99、自增 100、自减 101、结束 102、设备 103~132。
注意 `decode(int)` 里先 `& 0xFF`，否则 103 以上在 Java 的 `byte` 里是负数。

### `Cpu`（158 行）
三个寄存器 `pc / x / ir`，一次执行一条指令：

```java
CpuOutcome outcome = cpu.executeStep(memory, pcb);
switch (outcome.getKind()) {
  case VALUE_UPDATED:  ...   // 普通指令
  case DEVICE_REQUEST: ...   // 要申请设备
  case END:            ...   // 程序结束
  case INVALID:        ...   // 非法指令 / PC 越界
}
```

`executeStep` 从**内存副本**取指令（`memory.read(pcb.getMemoryStart() + pc)`），
不是从磁盘文件取——这就是"装入主存后执行"。所以运行中的进程即使源文件被删也不受影响
（`IntegratedScenarioTest.runningProcessSurvivesSourceDeletion` 验证了这一点）。

`saveTo(pcb)` / `restoreFrom(pcb)` 就是保存/恢复现场。

### `CpuOutcome`（98 行）
不可变的结果对象：`kind + instruction + xValue + deviceType + duration + note`。

---

## 4. mem 包：存储管理

### `MemoryManager`（221 行）
512 字节用户区，分区表是"按地址有序的 List"。

```java
int start = memory.allocate(pid, code.length);  // 首次适配，返回 -1 表示装不下
memory.loadProgram(start, code);                // 把指令副本写进用户区
memory.release(pid);                            // 回收并合并相邻空闲区
```

`allocate` 的算法：从头扫描分区表，找到第一个"空闲且足够大"的分区，
把它的前 size 字节分出去，剩下的变成一个新区间插到后面。
`release` 把分区标记为空闲，然后 `mergeFreePartitions()` 合并所有相邻空闲区。

### `MemoryPartition`（76 行）
一个分区：`start / size / ownerPid / loaded`，`FREE_OWNER = -1` 表示空闲。

---

## 5. dev 包：设备管理

### `DeviceManager`（230 行）
8 台设备的分配表 + 3 条等待队列。

```java
if (devices.request(DeviceType.A, pid, 3)) { ... }   // 有空闲：直接分配
else { devices.enqueue(DeviceType.A, pid, 3); }      // 没有：排队
List<Device> done = devices.tickDevices();            // 倒计时，返回刚归零的
devices.dispatchToNext(device);                       // 释放后直接交给队首
```

注意 `dispatchToNext` 会 `poll` 队首并把设备分配给它的 pid，
但**不会**改 PCB 状态——唤醒进程是 `OperatingSystem` 的职责（分层）。

### `Device` / `DeviceType` / `DeviceRequest`
一台设备（编号形如 `A1`、`B3`）、设备类别（含实例数）、等待队列元素（record）。

---

## 6. proc 包：进程管理

### `ProcessManager`（398 行）

```java
Pcb p = processes.createProcess("\\BIN\\P01.E", clock);  // PCB 满返回 null
processes.block(p, "占用A类设备（剩3）", DeviceType.A);
processes.wake(p);
processes.preemptRunning();                               // 时间片用完，放回队尾
Pcb next = processes.dispatch();                          // 取就绪队首，空则 IDLE
processes.destroy(p, clock);                              // 回收内存/设备/PCB
```

四个队列 + 一个 PCB 池（`Pcb[10]`）+ 一个闲逛进程。

`destroy` 的顺序很关键：先 `removeFromQueues`，再 `memory.release`、
`devices.releaseAll`、`devices.removeWaiter`（防止队列里留"幽灵"），
最后把 PCB 放回池子并记入 `finishedQueue`。

### `Pcb`（213 行）
字段：`pid / programPath / state / pc / x / memoryStart / memorySize /
deviceType / submitTime / runTime / sliceRemaining / blockedReason / finishTime /
currentInstruction / lastResult`。

`IDLE_PID = 0`，闲逛进程 `isIdle() == true`。

### `ProcessState` / `ProgramLoader`
五个状态（含中文标签）；`ProgramLoader` 是函数式接口 `byte[] load(String path)`。

---

## 7. engine 包：内核

### `OperatingSystem`（665 行）
**最该精读的类**。对外方法：

| 方法 | 说明 |
|---|---|
| `tick()` | 推进一个时间单位（暂停时无效） |
| `stepOnce()` | 无视暂停推进一个单位（单步按钮） |
| `runTicks(n)` | 连续推进 n 步（测试用） |
| `startProcess(path)` | 创建进程，失败抛 `FsException` |
| `killProcess(pid)` | 手动撤销 |
| `blockProcess(pid, reason)` / `resumeProcess(pid)` | 人工阻塞/唤醒 |
| `resetSystem()` | 清空进程/内存/设备，时钟归零，磁盘保留 |
| `formatDisk(bool)` | 格式化 + 可选重装默认文件树 |
| `snapshot()` | 生成界面快照 |
| `setQuantum / setTickIntervalMs / setAutoArrival` | 运行时配置 |

私有方法就是 7 步时钟的每一段，见 `ARCHITECTURE.md` 第 4 节。

### `SystemSnapshot`（123 行）
界面数据源。内部有 `PcbView` 和 `DeviceView` 两个 record。

### `EventLog` / `LogEntry` / `LogLevel`
容量 500 的环形日志，`entries()` 返回副本。

---

## 8. cmd 包：命令

### `Shell`（589 行）

```java
Shell shell = new Shell(os);
CommandResult r = shell.execute("run \\BIN\\P01.E");
if (!r.isSuccess()) System.out.println(r.getMessage());
```

`execute` 做三件事：切词 → `dispatch` 到 `cmdXxx` → 捕获异常转成 `CommandResult`。
每个 `cmdXxx` 都是"解析参数 → 调 `FileSystem`/`OperatingSystem` → 拼一段中文输出"。

`currentDir` 支持相对路径；`getPrompt()` 返回 `\DOC>` 这样的提示符。

### `CommandResult` / `EditorService` / `ConfirmService`
返回值对象；两个函数式接口把"弹编辑器""弹确认框"从命令层解耦出去。

---

## 9. ui 包：界面

只列出职责，细节见代码注释：

| 类 | 职责 |
|---|---|
| `MainFrame` | 组装 8 个区域、菜单/工具栏、`refresh()`、`launch()` |
| `SimulationDriver` | **唯一的 Timer**，`start/stop/step/setIntervalMs` |
| `ControlPanel` | 启动暂停、单步、重置、时间片、速度、随机到达、创建进程 |
| `ProcessPanel` | 进程表 + 就绪/阻塞/等内存/完成四个队列，右键菜单 |
| `MemoryPanel` | 512 字节分区图（最小 3px 宽，避免分区看不见） |
| `DevicePanel` | 8 张设备卡片 + 等待队列 |
| `FileSystemPanel` | 目录树 + 内容/反汇编预览 + 右键菜单 |
| `DiskGridPanel` | 32×8 块网格，点击看 FAT 值与归属 |
| `CommandPanel` | 命令输入与彩色输出 |
| `LogPanel` | 按级别着色的日志列表 |
| `EditorDialog` | 文本/汇编编辑，保存时编译 |
| `Theme` | 颜色、字体（中文用 `CONSOLE_FONT_NAME`，数字用等宽字体） |
| `CardPanel` / `FlatButton` / `SnapshotAware` | 绘制辅助 |
| `util/TextUtil` | 中英文宽度对齐（控制台表格） |

**约束**：面板只能通过 `refresh(snapshot)` 读数据。想在界面上加功能，
先想清楚"这个动作该调 `Shell` 还是 `OperatingSystem`"，不要直接改 `FileSystem`。

---

## 10. 两条完整调用链

### 10.1 用户输入 `run \BIN\P04.E`

```
CommandPanel 回车
 └─ Shell.execute("run \BIN\P04.E")
     ├─ dispatch → cmdRun
     ├─ resolvePath → "\BIN\P04.E"
     └─ OperatingSystem.startProcess(path)
         ├─ fileSystem.stat(path)              检查存在/可执行/非空
         ├─ ProcessManager.createProcess(...)
         │   ├─ findFreeSlot()                 取空闲 PCB
         │   ├─ programLoader.load(path)       FileSystem.readFile → 字节数组
         │   ├─ memory.allocate(pid, len)      首次适配；失败 → 进等内存队列
         │   ├─ memory.loadProgram(start, code)
         │   └─ readyQueue.addLast(pcb)
         └─ log.memory / log.process           写日志
 └─ CommandResult.ok("已创建进程 P1 ← \BIN\P04.E，已进入就绪队列")
```

之后某个 tick 的第 ③ 步 `dispatchNext()` 会把它投入运行，第 ④ 步执行第一条指令。

### 10.2 一次 `tick()`（有进程在跑、有设备在倒计时）

```
SimulationDriver.timer 触发
 └─ OperatingSystem.tick()
     ├─ ① clock++
     ├─ ② handleDeviceCountdown()
     │    └─ devices.tickDevices() → 归零的设备
     │        ├─ devices.release(device)
     │        ├─ processes.wake(owner)        → 就绪队列
     │        └─ handDeviceToNextWaiter()     → 队列非空则直接分配
     ├─ ③ 若 running == null → dispatchNext() → cpu.restoreFrom(pcb)
     ├─ ④ executeOneInstruction(running)
     │    ├─ cpu.executeStep(memory, pcb)
     │    ├─ cpu.saveTo(pcb)
     │    └─ 按 kind 分支：值更新 / 设备申请 / end / 非法
     ├─ ⑤ 时间片 -1，减到 0 → handleTimeSliceInterrupt() → preempt + dispatch
     ├─ ⑥ processes.retryPendingProcesses()
     └─ ⑦ handleRandomArrival()
 └─ MainFrame.refresh() → os.snapshot() → 8 个面板各自 refresh
```

---

## 11. 阅读建议

1. **先跑起来**：`./scripts/mvn.sh test`，看 88 个用例名字，就知道系统支持什么。
2. **带着问题读**：比如"时间片到底在哪一行切换的"→ 搜 `handleTimeSliceInterrupt`。
3. **改一个小地方验证理解**：把 `DEFAULT_QUANTUM` 从 6 改成 2，观察 P03 的切换次数变化。
4. **看测试当文档**：`ProcessSchedulingTest` 里每个用例的注释就是一份行为规格说明。
