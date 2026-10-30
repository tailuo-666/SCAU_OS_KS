# SCAU-OS 四人分工与代码阅读任务

> 配套文档：`docs/CODE_INDEX.md`（全量行号索引，自动生成）
> 适用场景：课程设计小组分工、代码走查、答辩讲解分工
> 行号说明：本文所有 `文件:行号` 均为**当前源码真实行号**，点击可跳转；若改过代码，先跑一次
> `python scripts/gen_index.py` 刷新索引，再对照本文。

---

## 0. 一页速览

| 成员 | 权重 | 定位 | 负责模块 | 主代码 | 测试 | 核心交付 |
| --- | --- | --- | --- | --- | --- | --- |
| **A** | **0.40** | 内核与总体架构（后端专家） | `engine` `proc` `mem` `dev` `exec` `util` `Main` | 3071 行 | 975 行 | 统一时钟与调度、五个模块的粘合、**前后端数据契约** |
| **B** | 0.20 | 文件系统与磁盘存储 | `fs`（11 个类） | 2432 行 | 407 行 | FAT 布局、目录树、跨块读写、持久化 |
| **C** | 0.20 | 命令层与界面框架 | `cmd`（4 个类）+ `ui` 框架 8 个类 | 1929 行 | 244 行 | 20+ 条命令、主窗口布局、唯一节拍器 |
| **D** | 0.20 | 数据视图与交互面板 | `ui` 中 7 个类 | 1828 行 | — | 进程/内存/设备/磁盘/文件 5 个监控面板 |

**前后端切分**：A + B = 6907 行（63.5%，纯后端，无一行 Swing 绘制）；C + D = 3757 行（34.5%，界面与交互）。
A 不看、也不讲任何面板的绘制代码，他只负责"往前端递什么数据"。

---

## 1. 为什么这样切

三个原则，答辩被问"为什么这么分工"时直接讲这三条：

1. **按数据流竖切，不按文件数横切。**
   磁盘块 → FAT/目录 → 文件系统 → 内核调度 → 命令层 → 界面。
   每个人手上都是一段"讲得完整、能独立演示"的竖向切片，而不是"我改了 12 个文件但说不清干了啥"。

2. **每个人都有一个能单独跑起来的入口。**
   B 用 `FileSystemTest` 跑，A 用 `OperatingSystem.runTicks()` 跑，C 用 `ShellTest` 跑，
   D 没有自动化测试（Swing 面板），所以给他配了截图回归脚本 + 手工检查清单。

3. **权重 0.40 的人做"难且不可替代"的部分。**
   内核的价值不在行数，而在**时序正确**：同一份代码，7 个步骤换个顺序就会出现
   "第一拍没有进程在跑""时间片中断早了一拍"这类只有写测试才能发现的错。
   这部分交给 A，同时把"给前端什么数据"的契约也交给他——界面怎么画是 C/D 的自由，
   但**字段谁提供、谁消费**必须由一个人拍板，否则前后端一定扯皮。

---

## 2. 成员 A — 权重 0.40（可上调至 0.45）

### 2.1 你负责什么

| 文件 | 行数 | 必须能讲的方法 |
| --- | --- | --- |
| `engine/OperatingSystem.java` | 650 | `tick()`:118、`tickInternal()`:137、`executeOneInstruction()`:180、`handleTimeSliceInterrupt()`:244、`handleDeviceCountdown()`:261、`dispatchNext()`:304、`snapshot()`:594 |
| `engine/SystemSnapshot.java` | 110 | 字段表 39~77、`PcbView`:80、`DeviceView`:104 |
| `proc/ProcessManager.java` | 383 | `createProcess()`:92、`retryPendingProcesses()`:129、`destroy()`:162、`block()`:192、`wake()`:212、`dispatch()`:232、`preemptRunning()`:250 |
| `proc/Pcb.java` | 202 | 字段 35~59、构造 `Pcb()`:61、`isIdle()`:192 |
| `mem/MemoryManager.java` | 204 | `allocate()`:65、`release()`:99、`mergeFreePartitions()`:113、`loadProgram()`:127 |
| `dev/DeviceManager.java` | 215 | `request()`:60、`dispatchToNext()`:89、`tickDevices()`:109、`release()`:123、`releaseAll()`:128、`removeWaiter()`:140 |
| `exec/Cpu.java` | 141 | `executeStep()`:94 |
| `exec/InstructionCodec.java` | 298 | `decode()`:129、`assemble()`:174、`disassemble()`:238 |
| `util/TextUtil.java` + `Main.java` | 145 | `displayWidth()`、`main()`:38、`resolveWorkDirectory()`:60 |

**你不负责**：`ui` 包里任何绘制代码。别人问"这个格子怎么画的"，答"去看 D 的 `DiskGridPanel`"，你只解释"我交给它的 `fat[]` 数组是什么意思"。

### 2.2 阅读顺序（照这个顺序读，不要跳）

1. **`Pcb.java:35-59`** — 先认清"进程"到底由哪些数字构成。
   重点：`pc`、`x`、`memoryStart`、`sliceRemaining` 四个字段就是"现场"。
   *要能回答*：进程被换下 CPU 时，哪些字段必须保存？（答：全部，因为它们都在 PCB 里，换 PCB 就等于换现场）

2. **`OperatingSystem.java:137` 的 `tickInternal()`** — 全项目的心脏。
   ①②③④⑤⑥⑦ 七步顺序是硬约定，测试断言依赖它。
   *要能回答*：为什么"调度"必须在"执行指令"之前？（答：否则第一拍没有运行进程，会白跑一拍）

3. **`ProcessManager.java:232` 的 `dispatch()`** — 只做三件事：从队首取、重置时间片、置 RUNNING。
   *要能回答*：就绪队列为空时返回什么？（答：闲逛进程，pid=0，让"当前进程"永不为 null）

4. **`OperatingSystem.java:244` 的 `handleTimeSliceInterrupt()`** — 时间片用完怎么切换。
   注意"保存现场 → 回就绪队列队尾 → 重新调度"三步。

5. **`MemoryManager.java:65` / `:99` / `:113`** — 首次适应 + 拆分 + 合并。
   *要能回答*：为什么释放时一定要合并？（答：不合并会产生外部碎片，总空闲够但找不到连续块）

6. **`DeviceManager.java:60` / `:89` / `:109`** — 设备申请、排队、倒计时、把设备直接交给队首。
   *要能回答*：设备归零后为什么不"还给系统"而是"直接给队首"？（答：避免刚释放就被新来的抢走，排队的第一个饿死）

7. **`Cpu.java:94` 的 `executeStep()`** — 取指、译码、执行三步，一次只做一条。
   *要能回答*：申请设备时 CPU 为什么不阻塞？（答：CPU 只报告"我想申请"，阻塞是内核的决定，见 `CpuOutcome`）

8. **`OperatingSystem.java:594` 的 `snapshot()`** — 前后端唯一的接口。

### 2.3 你交给前端的"数据契约"（这是你的核心产出，比代码更重要）

界面要显示任何东西，都必须先在这张表里找到字段。**表里没有的，C/D 不许自己去内核里翻。**

| 字段（`SystemSnapshot`） | 行号 | 含义 | 谁消费 |
| --- | --- | --- | --- |
| `clock` | 39 | 逻辑时钟（第几拍） | ControlPanel、LogPanel |
| `quantum` | 40 | 当前时间片长度 | ControlPanel、ProcessPanel |
| `paused` / `autoArrival` / `nextArrival` | 41~43 | 运行状态、自动到达开关与倒计时 | ControlPanel |
| `running` | 46 | 当前占用 CPU 的进程（可能是闲逛进程） | ProcessPanel 现场栏 |
| `ready` / `blocked` / `pending` / `finished` | 47~50 | 四个队列 | ProcessPanel 队列行、角标 |
| `all` | 52 | 全部活跃进程（**不含闲逛进程**） | ProcessPanel 表格 |
| `partitions` | 55 | 内存分区表（含空闲区） | MemoryPanel 条 + 表 |
| `memoryUsed` / `memoryTotal` | 56~57 | 已用 / 总量（512 字节） | MemoryPanel 使用率 |
| `devices` | 60 | 8 台设备的占用者与剩余拍数 | DevicePanel 卡片 |
| `waitQueues` | 62 | A/B/C 三类等待队列（PID 列表） | DevicePanel 队列区 |
| `deviceBusy` / `deviceTotal` | 63~64 | 占用数 / 总数 | DevicePanel 角标 |
| `tree` | 67 | 目录树快照（`DirNode`） | FileSystemPanel 树 |
| `fat` / `blockOwners` | 68~69 | 256 项 FAT 表 + 每块归属说明 | DiskGridPanel 网格 |
| `freeBlocks` / `usedBlocks` / `dataBlocks` | 70~72 | 空闲 / 已用 / 数据区总块数 | DiskGridPanel、FileSystemPanel 角标 |
| `openFileCount` / `openFilePaths` | 73~74 | 打开文件表（最多 5） | FileSystemPanel 角标 |
| `logs` | 77 | 最近若干条日志 | LogPanel |
| `PcbView`（record） | 80 | 进程行视图：pid/name/state/program/pc/x/slice/instruction/idle… | ProcessPanel 每一列 |
| `DeviceView`（record） | 104 | 设备卡视图：id/type/busy/ownerName/remaining | DevicePanel 每张卡 |

**契约的三条规矩**（讲给 C/D 听）：

1. 快照是**不可变副本**：面板只读，改它不影响内核。
2. 界面要加一列 → 先在 `PcbView` 里加字段，由 A 实现赋值，D 再取。**不许**让面板直接持有 `Pcb`。
3. 新增字段要同步三处：`PcbView`/`DeviceView` 定义、`snapshot():594` 里的赋值、本文这张表。

### 2.4 你要能回答的 6 个问题（别人最常问）

| 问题 | 答案要点 | 代码位置 |
| --- | --- | --- |
| 为什么全系统只有一个 `Timer`？ | 多个定时器会各扣各的倒计时，状态不可复现；单一时钟才能断言"第 6 拍一定中断" | `SimulationDriver.java:44`、`OperatingSystem.java:137` |
| 时间片是怎么"记住还剩几拍"的？ | `Pcb.sliceRemaining`，每次成功执行一条指令后减 1，减到 0 触发中断 | `OperatingSystem.java:158-165` |
| 同一个进程能重复占内存吗？ | 不能，`allocate()` 里查 `partitionOf(pid)` 后直接抛异常 | `MemoryManager.java:72` |
| 进程被杀时设备怎么办？ | `releaseAll(pid)` 一次性收回，并从等待队列 `removeWaiter(pid)` 删掉，防幽灵排队 | `DeviceManager.java:128`、`:140` |
| 内存不够时进程会被杀掉吗？ | 不会，进"等内存队列"（`pending`），每拍重试一次 | `ProcessManager.java:129` |
| 为什么要闲逛进程？ | 让"当前进程"永不为 null，界面和内核都不用写空判断 | `ProcessManager.java:232`、`Pcb.java:192` |

### 2.5 你的交付物

- 文档：`docs/ARCHITECTURE.md`（总体架构）、`docs/PROCESS_FLOW.md`（逐拍推演）、
  `docs/MEMORY_DEVICE.md`（内存与设备）、本文 2.3 节的契约表
- 测试：`MemoryManagerTest`（12）、`DeviceManagerTest`（11）、`InstructionCodecTest`（10）、
  `ProcessSchedulingTest`（13）、`IntegratedScenarioTest`（7）——共 53 个用例
- 答辩：主答"调度 / 中断 / 内存 / 设备 / 前后端契约"五类问题

---

## 3. 成员 B — 权重 0.20

### 3.1 你负责什么

| 文件 | 行数 | 必须能讲的方法 |
| --- | --- | --- |
| `fs/Fat.java` | 340 | `isReservedBlock()`:91、`blockOfEntry()`:113、`offsetOfEntry()`:121、`allocateChain()`:209、`freeChain()`:235、`chainOf()`:259、`appendBlock()`:278 |
| `fs/FileSystem.java` | 921 | `format()`:108、`mkdir()`:182、`createFile()`:266、`readFile()`:300、`writeFile()`:317、`deleteFile()`:342、`copyFile()`:363、`moveFile()`:401、`openFile()`:459、`readOpenedFile()`:493、`buildTree()`:625、`directoryChain()`:699、`lookup()`:760、`writeNewChain()`:873 |
| `fs/DirEntry.java` | 221 | `encode()`:68、`decode()`:81、`getFullName()`:214 |
| `fs/Disk.java` | 176 | `readBlock()`:80、`writeBlock()`:95、`flush()`:126 |
| `fs/OpenFileTable.java` | 86 | `MAX_OPEN_FILES`:23、`find()`:28、`allocate()`:42、`close()`:56 |
| `fs/PathUtil.java` | 144 | `normalize()`:37、`resolve()`:72、`splitName()`:131 |
| `fs/DefaultFileTree.java` | 149 | `install()` — 默认的 5 目录 / 15 文件 / 10 个可执行程序 |

### 3.2 阅读顺序

1. `Disk.java:80` / `:95` — 磁盘只会"读一块、写一块"，什么都不懂。先建立这个认知。
2. `Fat.java:113` — `blockOfEntry()`：FAT 逻辑下标 → 物理块号。**这是全项目最绕的一个函数**，
   因为 FAT 被拆成"块 0、1 + 块 3、4"两段，中间夹着根目录。
3. `Fat.java:209` `allocateChain()` — 先挑后占、串成链、立刻落盘。
4. `Fat.java:259` `chainOf()` — 顺着链走，遇到 255 结束。
   *易错点*：不能拿"表项值 == 254"判断保留块，254 是合法块号。
5. `DirEntry.java:68` / `:81` — 8 字节目录项的编码与解码。
6. `FileSystem.java:266` `createFile()` — 一次完整的"建文件"：找父目录 → 找空块 → 写数据 → 加目录项。
7. `FileSystem.java:317` / `:300` — 跨块写与跨块读（写入超过 64 字节会自动申请新块）。
8. `FileSystem.java:699` `directoryChain()` — 根目录固定第 2 块，不在数据区，所以要特判。
9. `OpenFileTable.java:42` — 打开文件表最多 5 项，第 6 个报错。

### 3.3 你要能回答的 5 个问题

| 问题 | 答案要点 | 代码位置 |
| --- | --- | --- |
| FAT 为什么占 5 个块？课程不是说 2 个吗？ | 256 项 × 1 字节 = 256 字节，2 块只有 128 字节，数学上装不下。本项目保证"256 项 + 根目录固定第 2 块"，把后 128 项放块 3、4，块 255 保留（否则和"链结束 255"冲突） | `Fat.java` 类注释 + `README.md` 第 3 节 |
| 一个目录能放多少个文件？ | 每块 8 项，满了就申请新块、用 FAT 串起来，所以理论上不限（受总块数限制） | `FileSystem.java:699` |
| 文件超过 64 字节会怎样？ | 自动申请下一块并串链；读的时候沿链拼回来 | `FileSystem.java:873`、`:300` |
| 关掉程序再打开，文件还在吗？ | 在。所有改动都 `flush()` 到 `disk.img`，启动时 `open()` 读回来 | `Disk.java:126`、`FileSystem.java:62` |
| 磁盘满了报什么错？ | `FsException("磁盘空间不足…")`，命令行显示红色错误，不会崩溃 | `Fat.java:209` 返回 -1，上层抛异常 |

### 3.4 交付物

- 文档：`docs/FILESYSTEM.md`
- 测试：`FileSystemTest`（22 个用例，覆盖跨块、目录满、空间不足、只读、持久化）
- 答辩：主答"磁盘布局 / FAT / 目录 / 打开文件表"四类问题

---

## 4. 成员 C — 权重 0.20

### 4.1 你负责什么

| 文件 | 行数 | 必须能讲的方法 |
| --- | --- | --- |
| `cmd/Shell.java` | 573 | `execute()`:88、`dispatch()`:112、`cmdDir()`:247、`cmdCreate()`:352、`cmdEdit()`:431、`cmdRun()`:457、`case "fdisk"`:230 |
| `cmd/CommandResult.java` | 50 | 成功/失败 + 文字，界面靠它决定红黑 |
| `cmd/ConfirmService.java` / `EditorService.java` | 37 | 两个"解耦接口"：命令行不依赖 Swing |
| `ui/MainFrame.java` | 322 | 构造 `:66`、`buildCenter()`:119、`buildBottom()`:153、`buildMenu()`:163、`refresh()`:234、`applyDefaultDividers()`:315、`launch()`:323 |
| `ui/SimulationDriver.java` | 66 | `start()`:44、`stop()`:52、`setIntervalMs()`:64、`step()`:71 |
| `ui/ControlPanel.java` | 267 | 构造 `:67`、`buildFirstRow()`:90、`buildSecondRow()`:115 |
| `ui/Theme.java` | 232 | `install()`:125、`processColor()`:158、`stateForeground()`:174 |
| `ui/CardPanel.java` | 77 | `setBadge()`:42、`paintComponent()`:48 |
| `ui/FlatButton.java` / `SnapshotAware.java` / `LogPanel.java` | 198 | `LogPanel.update()`:61、日志渲染器 `:82` |

### 4.2 阅读顺序

1. `Shell.java:88` `execute()` — 切词 → 分发 → 异常翻译成人话。三类异常各 catch 一次。
2. `Shell.java:112` `dispatch()` — 一张 switch 表，20+ 条命令的分发关系全在这。
3. `Shell.java:457` `cmdRun()` — `run` 命令怎么把一个文件变成进程（调 `os.startProcess`）。
4. `MainFrame.java:66` 构造器 — 8 个区域怎么切（北：控制条，中：三栏，南：日志/命令页签）。
5. `MainFrame.java:234` `refresh()` — 取一份快照，喂给所有 `SnapshotAware`。
6. `SimulationDriver.java:44` — 唯一的 `javax.swing.Timer`，`step()` 是单步。
7. `Theme.java:125` `install()` — 为什么中文和数字要用两套字体。

### 4.3 你要能回答的 4 个问题

| 问题 | 答案要点 | 代码位置 |
| --- | --- | --- |
| 命令是怎么被解析的？ | 按空白切分，第 0 个是命令名（转小写），其余是参数 | `Shell.java:93` |
| 为什么界面不直接调 `FileSystem`？ | 要走命令层，才能复用"路径解析 + 错误提示 + 日志记录"；否则 20 个按钮要写 20 套错误处理 | `Shell.java:88` |
| `edit` 命令怎么弹出窗口却不依赖 Swing？ | `EditorService` 是个接口，界面层实现，测试层传空实现 | `EditorService.java`、`MainFrame.java:66` |
| 单步和连续跑有什么区别？ | 单步调 `stepOnce()`（忽略暂停标志），连续跑由 Timer 调 `tick()` | `OperatingSystem.java:126`、`SimulationDriver.java:71` |

### 4.4 交付物

- 测试：`ShellTest`（13 个用例）
- 文档：`docs/LEARNING_GUIDE.md`（新手入门路线）
- 答辩：主答"命令层 / 界面架构 / 前后端解耦"三类问题

---

## 5. 成员 D — 权重 0.20

### 5.1 你负责什么

| 文件 | 行数 | 必须能讲的方法 |
| --- | --- | --- |
| `ui/ProcessPanel.java` | 334 | 构造 `:64`、`configureTable()`:98、`maybeShowPopup()`:134（右键干预）、`update()`:185 |
| `ui/FileSystemPanel.java` | 511 | `update()`:164、`rebuildTree()`:192、`showPreview()`:284 |
| `ui/DiskGridPanel.java` | 240 | `update()`:114、`showDetail()`:128、网格绘制 `:216` |
| `ui/MemoryPanel.java` | 185 | `update()`:79、`MemoryBar.paintComponent()`:107 |
| `ui/DevicePanel.java` | 140 | `update()`:60、`DeviceCard.paintComponent()` |
| `ui/CommandPanel.java` | 173 | `executeCurrent()`:111、`runCommand()`:123 |
| `ui/EditorDialog.java` | 146 | `open()`:102、`doSave()`:123（编译后再写盘） |

### 5.2 阅读顺序

1. `SnapshotAware`（14 行）— 你所有面板都要实现这一个方法，先搞懂它。
2. `ProcessPanel.java:185` — 最典型的"快照 → 界面"：①表格 ②现场栏 ③队列 ④角标。
3. `MemoryPanel.java:79` + `:107` — 数据表 + 自绘条形图，学"按 size 算像素宽度"。
4. `DiskGridPanel.java:114` + `:128` — 256 格网格 + 点击查详情，**这是讲 FAT 最直观的地方**。
5. `FileSystemPanel.java:164` — 结构签名：为什么不是每拍重建树。
6. `CommandPanel.java:123` — 界面 → 命令层 → 界面的完整闭环。
7. `EditorDialog.java:123` — 保存前先汇编，汇编失败就拦住。

### 5.3 你要能回答的 4 个问题

| 问题 | 答案要点 | 代码位置 |
| --- | --- | --- |
| 面板自己存状态吗？ | 不存。所有数字来自 `update(snapshot)`，面板只是"显示器" | 全部 7 个文件的 `update()` |
| 为什么进程表没有 PID 0？ | 闲逛进程是内核机制，不是用户进程，显示出来会让人误解 | `ProcessPanel.java:189`（`snapshot.all` 已排除） |
| 目录树为什么不会每拍都折叠？ | 先算结构签名，签名不变就不重建；重建时记录并恢复展开路径 | `FileSystemPanel.java:164`、`:192` |
| 内存条上很小的分区怎么画？ | 最小宽度 3 像素，宁可失真也要看得见 | `MemoryPanel.java:121` |

### 5.4 交付物

- 5 张界面截图（`docs/images/`）+ 手工检查清单（`docs/TEST_REPORT.md` 第 7 节）
- 答辩：主答"界面怎么显示 / 右键怎么干预 / 为什么这么设计"三类问题

---

## 6. 讲解顺序建议（60 分钟演示脚本）

按"用户视角"讲，不要按包名讲。建议顺序：界面 → 命令 → 进程 → 内存 → 设备 → 磁盘 → FAT。

| 时间 | 讲者 | 内容 | 打开位置 |
| --- | --- | --- | --- |
| 0-3 min | D | 整体界面 8 个区域各看什么 | 运行中的窗口 |
| 3-8 min | C | 敲 `dir`、`run \BIN\P01.E`，看进程跑完 | `Shell.java:112`、`:457` |
| 8-18 min | A | 逐拍推演 P03（8 条指令 / 时间片 6）→ 为什么第 6 拍切换 | `OperatingSystem.java:137`、`PROCESS_FLOW.md` |
| 18-26 min | A | 三个 P04 抢 2 台 A 设备 → 谁排队、谁被唤醒 | `DeviceManager.java:60`、`:109` |
| 26-34 min | A | 内存条随进程创建/结束变化 → 首次适应与合并 | `MemoryManager.java:65`、`:99` |
| 34-44 min | B | 点磁盘网格看某块归谁 → 讲 FAT 链与布局妥协 | `DiskGridPanel.java:128`、`Fat.java:113` |
| 44-52 min | B | 新建一个超过 64 字节的文件 → 跨块 → 重开程序还在 | `FileSystem.java:266`、`:300` |
| 52-60 min | C | 讲前后端怎么解耦（`SnapshotAware` / `EditorService`） | `MainFrame.java:234`、`EditorService.java` |

---

## 7. 交叉提问（准备被问到就背这张表）

| # | 提问 | 该谁答 | 答案落点 |
| --- | --- | --- | --- |
| 1 | 时间片用完发生了什么？ | A | `OperatingSystem.java:244` |
| 2 | 闲逛进程有什么用？ | A | `ProcessManager.java:232` |
| 3 | 内存碎片怎么解决？ | A | `MemoryManager.java:113` |
| 4 | 设备等待队列按什么顺序？ | A | `DeviceManager.java:70` |
| 5 | X 会不会变成负数？ | A | `Cpu.java` 的 DECREMENT 分支（饱和在 0） |
| 6 | 一条指令怎么变成 1 个字节？ | A | `InstructionCodec.java:174` |
| 7 | 界面数据从哪来？ | A | `SystemSnapshot.java:39-77` |
| 8 | FAT 为什么不是 2 个块？ | B | `Fat.java` 类注释、`README.md` §3 |
| 9 | 目录能放超过 8 个文件吗？ | B | `FileSystem.java:699` |
| 10 | 文件超过 64 字节怎么办？ | B | `FileSystem.java:873` |
| 11 | 打开文件表满了呢？ | B | `OpenFileTable.java:42` |
| 12 | 关机后文件还在吗？ | B | `Disk.java:126` |
| 13 | 根目录为什么特殊？ | B | `FileSystem.java:699` |
| 14 | 命令怎么解析？ | C | `Shell.java:92` |
| 15 | 为什么只有一条命令的 switch？ | C | `Shell.java:112` |
| 16 | 界面为什么不卡？ | C | 单 Timer + 快照，`SimulationDriver.java:44` |
| 17 | 中文为什么不是方块？ | C | `Theme.java:125`（两套字体） |
| 18 | 分割条位置为什么会被重置？ | C | `MainFrame.java:315` |
| 19 | 面板怎么刷新？ | D | `ProcessPanel.java:185` |
| 20 | 磁盘网格颜色代表什么？ | D | `DiskGridPanel.java:114` |
| 21 | 右键能做什么？ | D | `ProcessPanel.java:134` |
| 22 | 编辑器保存时做了什么？ | D | `EditorDialog.java:123` |

---

## 8. 权重调档（如果组内对 0.40 有异议）

| 方案 | A | B | C | D | 适用场景 |
| --- | --- | --- | --- | --- | --- |
| **主方案** | 0.40 | 0.20 | 0.20 | 0.20 | 按本文分工，A 只做内核 + 契约 |
| 加压方案 | 0.45 | 0.20 | 0.18 | 0.17 | A 额外承担：88 个测试的持续维护、集成调试、答辩主答、全部文档统稿 |
| 按行数方案 | 0.37 | 0.26 | 0.20 | 0.17 | 完全按代码行数分（A 4046 / B 2839 / C 2173 / D 1828），不看难度 |

**为什么主方案不是纯按行数**：B 的 26.1% 行数只给 0.20，是因为文件系统是最"有教材可抄"的部分——
FAT、目录项、跨块读写都是标准算法，出错能立刻定位；而 A 的 37.2% 里有一段"时序不能错"的调度逻辑，
和一份"改一处要同步三处"的契约表，返工成本远高于行数比例。D 只有 16.8% 行数却给 0.20，
是因为界面没有自动化测试，每改一次都要人工截图比对（本项目在字体、分割条、网格布局上就返工了 5 轮）。

---

## 9. 关于本项目新加的"面向初学者注释"

为了让四个人都能在**不看别人代码的情况下**读懂自己那部分，源码做了两层注释（共加了约 700 行）：

1. **每个类的 `新手阅读导航`**（51 个类全有）——固定四问：
   `是什么` / `从哪读起` / `易错点` / `分工（谁主讲、谁会问什么）`。
   例：`Fat.java` 类注释里直接写明"254 是保留块标记，不是块号"这个真实踩过的坑。

2. **关键方法的逐行说明**——只在"算法主干"上写，不写废话：
   `Fat.allocateChain()`、`Fat.freeChain()`、`MemoryManager.allocate()/release()`、
   `Cpu.executeStep()`、`ProcessManager.dispatch()`、`DeviceManager.tickDevices()`、
   以及 5 个面板的 `update()` 方法（标了 ①②③④ 四段）。

3. **行内注释只解释"为什么"，不解释"是什么"**。
   例如 `MemoryManager.java` 里写的是"为什么不选最佳适应"，而不是"这里遍历分区列表"。

---

## 10. 索引怎么用

- 全量索引（每个类、每个方法、每个常量的行号）：**`docs/CODE_INDEX.md`**
- 重新生成：`python scripts/gen_index.py`
  它扫 `src/main/java` 与 `src/test/java`，按 `OWNER` 表把 51 个类分到 4 个人名下。
- 索引里的"一句话说明"优先取 Javadoc 首句，没有则取方法上方最近的 `//` 注释，都没有显示 `—`。
- 想改分工？只改 `gen_index.py` 里的 `OWNER` 字典，重跑一次，索引自动重排。
