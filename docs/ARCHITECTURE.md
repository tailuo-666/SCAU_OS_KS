# 总体架构

本文说明 SCAU-OS 的分层、模块职责、线程模型与数据流。看完这一篇，
再看 `CLASS_GUIDE.md` 就能顺着代码读下去。

---

## 1. 一句话架构

> 一台"用文件当磁盘"的模拟计算机：`OperatingSystem` 是主板，
> 5 个硬件模块（磁盘/FAT、内存、设备、CPU、进程表）各自独立，
> 统一时钟 `tick()` 一次推进 1 个时间单位；界面拿到一份只读快照后渲染。

---

## 2. 分层

```
┌──────────────────────────────────────────────────────────────┐
│  ui 层（Swing）                                               │
│  MainFrame / 8 个面板 / SimulationDriver（唯一的 Timer）        │
│  只读 SystemSnapshot，不修改任何模型                            │
└───────────────▲──────────────────────────┬───────────────────┘
                │ snapshot()               │ Shell.execute(命令)
┌───────────────┴──────────────────────────▼───────────────────┐
│  engine 层：OperatingSystem（内核）                            │
│  tick() 统一时钟、调度决策、中断处理、启动/撤销进程、日志        │
│  SystemSnapshot（快照）、EventLog（环形日志）                  │
└───┬──────────┬──────────┬──────────┬──────────┬──────────────┘
    │          │          │          │          │
┌───▼───┐ ┌────▼────┐ ┌───▼────┐ ┌───▼────┐ ┌───▼─────┐
│  fs   │ │  mem    │ │  dev   │ │  proc  │ │  exec   │
│文件系统│ │存储管理 │ │设备管理│ │进程管理│ │CPU+指令 │
└───┬───┘ └─────────┘ └────────┘ └───┬────┘ └────┬────┘
    │                               │           │
┌───▼──────────┐                    │      ┌────▼──────────┐
│ Disk(disk.img)│                   └──────► MemoryManager │
│ 256 块×64 字节 │                          │ DeviceManager │
└──────────────┘                            └───────────────┘
```

### 依赖方向（严格遵守，无环）

| 层 | 可以依赖 | 不能依赖 |
|---|---|---|
| `ui` | `engine`、`cmd`、各模块的只读类型 | 不能直接改 `FileSystem`/`MemoryManager` 的状态 |
| `cmd` | `engine`、`fs` | 任何 Swing 类 |
| `engine` | `fs`、`mem`、`dev`、`proc`、`exec` | `cmd`、`ui` |
| `proc` | `mem`、`dev`、`fs`（只读文件内容） | `engine`、`ui`、`cmd` |
| `fs`/`mem`/`dev`/`exec` | 只依赖 JDK 与同包 | 其他业务包 |

`proc → fs` 这一条是为了"创建进程时读可执行文件"，
用 `ProgramLoader` 函数式接口注入（`ProcessManager` 构造参数），
所以单元测试可以塞一个假的 loader，不需要真磁盘。

---

## 3. 五个硬件模块

| 模块 | 类 | 负责 | 不负责 |
|---|---|---|---|
| 磁盘 | `Disk` | 按块读写 `disk.img`（`RandomAccessFile`） | 不理解 FAT 与目录 |
| 文件系统 | `Fat` + `FileSystem` + `DirEntry` | 块分配、目录树、文件读写、打开文件表 | 不理解进程 |
| 存储 | `MemoryManager` | 512 字节用户区的分区与首次适配 | 不理解磁盘 |
| 设备 | `DeviceManager` | 8 台设备的分配、等待队列、倒计时 | 不理解进程状态 |
| CPU | `Cpu` | 取指、译码、执行一条指令、保存/恢复现场 | 不理解调度 |
| 进程 | `ProcessManager` | PCB、就绪/阻塞/等内存/完成队列、调度原语 | 不理解指令含义 |

`OperatingSystem` 只做"决策与粘合"：什么时候调度、设备用完了唤醒谁、
时间片到了切谁、进程结束了回收什么。这样每个模块都能单独测试。

---

## 4. 统一逻辑时钟：`tick()` 的 7 个步骤

整个系统**只有一个时间来源**。`OperatingSystem.tickInternal()` 严格按以下顺序执行：

```
① clock++                       系统时钟 +1
② handleDeviceCountdown()       占用中的设备倒计时 -1；
                                归零 → 释放设备 → 唤醒原进程 → 交给等待队列队首
③ dispatchNext()                若没有运行进程，从就绪队列取一个；
                                就绪队列空 → 运行闲逛进程 IDLE
    若 IDLE 正在运行但已有就绪进程 → 立刻让出 CPU
④ executeOneInstruction()       执行当前进程的一条指令：
                                - 普通指令：更新 X、PC+1
                                - 设备申请：分配成功则阻塞，失败则进等待队列
                                - end：撤销进程、回收资源
                                - 非法指令：报错并撤销
⑤ 时间片 -1                      若进程仍在该 tick 运行：
                                剩余时间片减 1，减到 0 → 时间片中断 → 换人
⑥ retryPendingProcesses()       给"等内存"的进程再试一次分配
⑦ handleRandomArrival()         随机到达：按倒计时创建一个新进程（可关闭）
```

**顺序为什么重要**：设备倒计时必须在执行指令之前，
否则"刚申请的设备"会在同一个时间单位里被扣掉一次；
调度必须在执行之前，否则新创建的进程要多等一个时间单位。
测试里的所有精确断言都依赖这个顺序（见 `PROCESS_FLOW.md`）。

暂停开关 `paused` 只影响 `tick()`；`stepOnce()` 无视暂停，用于单步调试。

---

## 5. 线程模型

```
        EDT（Swing 事件线程）
  ┌──────────────────────────────────┐
  │ 按钮/菜单事件 → 直接调模型方法      │
  │ （startProcess / setQuantum / ...）│
  │                                  │
  │ javax.swing.Timer（唯一）         │
  │   每 tickIntervalMs 触发一次：     │
  │     os.tick() → 生成 Snapshot     │
  │     → 各面板 refresh(snapshot)    │
  └──────────────────────────────────┘
```

- **只有一个 Timer**，在 `SimulationDriver` 里创建；速度滑块改变的是
  `Timer.setDelay()`，不会创建第二个 Timer。
- 所有模型方法都在 EDT 上调用，因此模型本身不加锁、不用 `synchronized`。
- 磁盘 IO（`RandomAccessFile`）的 `readBlock`/`writeBlock` 仍是 `synchronized` 的，
  这是防御性写法——即使将来把模型挪到别的线程也不会撕裂块。

**为什么不用多线程模拟"多道程序"**：
真实线程 + `sleep` 会让"某个时间单位设备少扣一次"这类 bug 变成不可复现的偶发问题，
也无法在测试里断言 `P3 在 tick 6 时 PC 应为 6`。逻辑时钟把时间变成可数的整数，
调试和演示都更可靠。

---

## 6. 快照：界面与模型的唯一接口

```java
SystemSnapshot s = os.snapshot();   // 一次性复制全部状态
processPanel.refresh(s);
memoryPanel.refresh(s);
diskGridPanel.refresh(s);
...
```

`SystemSnapshot` 里是**拷贝**而不是引用：

- 进程：`PcbView`（record，含 pid/状态/PC/X/内存/时间片/阻塞原因/当前指令）
- 内存：`List<MemoryPartition>` 的副本
- 设备：`DeviceView`（record，含编号/类型/占用者/剩余时长）
- 文件系统：`DirNode` 树副本、FAT 数组副本、每个块的归属者字符串数组
- 日志：`EventLog` 的条目副本

这样做有三点好处：

1. 8 个面板看到的一定是同一时刻的状态，不会互相矛盾；
2. 面板即使乱序刷新或延迟刷新，也不会读到"半个"状态；
3. 面板无法通过修改快照去改模型（快照是拷贝），从结构上保证界面只读。

`SystemSnapshot.all` 只包含运行/就绪/阻塞/等内存的进程，
不含闲逛进程（`Pcb.IDLE_PID = 0`），而且已完成进程单独放在 `finished` 里。
这是为了避免"进程表里突然多出一个 IDLE 行"造成困惑。

---

## 7. 日志

`EventLog` 是容量 500 的环形缓冲，条目是 `(时间, 级别, 消息)`：

| 级别 | 用在哪 |
|---|---|
| `SYSTEM` | 启动、重置、格式化、配置变更 |
| `PROCESS` | 创建、调度、时间片中断、阻塞、唤醒、结束 |
| `MEMORY` | 分配、等内存、回收 |
| `DEVICE` | 申请成功/失败、I/O 完成、直接交给队首 |
| `FILE` | 命令引起的文件变更 |
| `ERROR` | 非法指令、命令失败 |

日志只增不删（先进先出），不会因为长时间运行把内存吃光。

---

## 8. 错误处理约定

| 场景 | 做法 |
|---|---|
| 文件/目录业务错误（重名、不存在、只读、空间不足、表满） | 抛 `FsException`，中文消息可直接展示 |
| 指令汇编错误 | 抛 `AssemblyException`，带行号 |
| 参数越界（内存地址、设备时长） | 抛 `IllegalArgumentException` / `IllegalStateException` |
| 命令层 | 捕获上述异常，转成 `CommandResult.error("错误：...")`，并写一条 ERROR 日志 |
| 界面 | 不捕获业务异常（由命令层兜住）；只在用户操作失败时弹提示 |

`FileSystem` 的关键写操作都是"先分配新块，成功后再释放旧块"，
所以"磁盘满时写文件"不会把原文件写坏（见 `writeFile` 注释）。

---

## 9. 与课程要求的对应关系

| 课程要求 | 实现位置 |
|---|---|
| 用文件模拟磁盘 | `fs/Disk.java`，`disk.img` 固定 16384 字节 |
| 位示图/空闲块管理 | `Fat`（FAT 表项 0 表示空闲，兼作位示图） |
| 显式链接 | `Fat.allocateChain / chainOf / appendBlock` |
| 树形目录、绝对/相对路径 | `FileSystem.lookup` + `PathUtil` |
| 目录项 8 字节 | `DirEntry.encode/decode` |
| 打开文件表（最多 5 个） | `OpenFileTable` |
| 首次适配、分区拆分与合并 | `MemoryManager.allocate / release` |
| 独占设备、等待队列 | `DeviceManager` |
| 时间片轮转、PCB、闲逛进程 | `ProcessManager` |
| 3 类中断（程序结束/时间片/IO） | `OperatingSystem` 的三个 handle 方法 |
| 统一时钟、可单步 | `tick() / stepOnce()` |
| 图形监控 8 个区域 | `ui/` 包 |
| 命令行 16+ 条命令 | `cmd/Shell.java`（实际 22 条） |

---

## 10. 文件清单（按职责）

```
fs/   Disk          块设备（RandomAccessFile）
      Fat           FAT 表（256 项，两段式物理布局）
      DirEntry      8 字节目录项的编解码
      DirNode       目录树只读快照（给界面）
      PathUtil      路径规范化、相对路径解析、名称拆分
      OpenFile      打开文件表项（读写指针）
      OpenFileTable 打开文件表（最多 5 项）
      FileAttribute 属性位工具
      FileSystem    文件系统门面（上层唯一入口）
      DefaultFileTree 首次格式化写入的默认文件树
      FsException   业务异常

exec/ Cpu             寄存器 PC/X/IR，执行一条指令
      InstructionCodec 汇编/反汇编/单字节编解码
      CpuOutcome     一条指令的执行结果（值更新/设备申请/结束/非法）
      AssemblyException

mem/  MemoryPartition 一个分区（起址、长度、归属 PID）
      MemoryManager   512 字节用户区、首次适配、合并

dev/  DeviceType      A/B/C 三类，实例数 2/3/3
      Device          一台设备（占用者、剩余时长）
      DeviceRequest   等待队列里的申请（record）
      DeviceManager   分配表 + 3 条等待队列 + 倒计时

proc/ Pcb             进程控制块
      ProcessState    NEW/READY/RUNNING/BLOCKED/DONE
      ProcessManager  PCB 池、4 个队列、调度原语
      ProgramLoader   函数式接口（读可执行文件）

engine/ OperatingSystem 内核与统一时钟
        SystemSnapshot  界面数据源
        EventLog/LogEntry/LogLevel 日志

cmd/  Shell           22 条命令的解析与执行
      CommandResult   成功/失败/是否清屏
      EditorService / ConfirmService  解耦界面能力的接口

ui/   MainFrame       主窗口与布局
      ControlPanel    控制区
      ProcessPanel    进程区（表 + 4 个队列）
      MemoryPanel     内存分区图
      DevicePanel     设备卡片与等待队列
      FileSystemPanel 目录树 + 预览
      DiskGridPanel   32×8 块网格
      CommandPanel    命令行
      LogPanel        日志
      SimulationDriver 唯一的 Timer
      EditorDialog    汇编/文本编辑器
      Theme / CardPanel / FlatButton / SnapshotAware 辅助

util/ TextUtil        中英文宽度对齐（控制台表格用）
```
