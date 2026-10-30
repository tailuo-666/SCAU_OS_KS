# SCAU-OS 代码索引（按负责人）

> 本文件由 `target/tools/gen_index.py` 扫描源码自动生成，行号与当前代码一一对应。

> 修改代码后重新执行 `python scripts/gen_index.py` 即可刷新。

> 行号含义：该成员定义（方法 / 常量）在文件中的起始行，便于直接跳转。


## 一、总览

| 成员 | 角色 | 权重 | 类数 | 主代码行 | 测试行 | 合计 | 占比 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | 内核调度与总体架构（后端专家） | 0.40 | 21 | 3071 | 975 | 4046 | 37.2% |
| B | 文件系统与磁盘存储 | 0.20 | 11 | 2432 | 407 | 2839 | 26.1% |
| C | 命令层与界面框架 | 0.20 | 12 | 1929 | 244 | 2173 | 20.0% |
| D | 数据视图与交互面板 | 0.20 | 7 | 1828 | 0 | 1828 | 16.8% |
| 合计 | — | 1.00 | 51 | 9260 | 1626 | 10886 | 100% |

---

## 成员 A · 内核调度与总体架构　权重 0.40

### `cn.scau.os.Device`　98 行　`src/main/java/cn/scau/os/dev/Device.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 21 | `NO_OWNER` | — |
| 29 | `Device()` | — |
| 37 | `getType()` | — |
| 41 | `getIndexInType()` | — |
| 46 | `getId()` | — |
| 50 | `getOwnerPid()` | — |
| 54 | `getRemaining()` | — |
| 58 | `isBusy()` | — |
| 63 | `assign()` | — |
| 75 | `release()` | — |
| 95 | `toString()` | — |

### `cn.scau.os.DeviceManager`　230 行　`src/main/java/cn/scau/os/dev/DeviceManager.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 42 | `DeviceManager()` | — |
| 60 | `request()` | 尝试立即分配一个空闲设备 |
| 70 | `enqueue()` | — |
| 75 | `pollWait()` | — |
| 80 | `peekWait()` | — |
| 89 | `dispatchToNext()` | 把设备直接交给队首等待者，并按它当初申请的时长开始倒计时 |
| 109 | `tickDevices()` | 所有占用中的设备倒计时减 1，返回本次刚好归零的设备（尚未释放） |
| 123 | `release()` | — |
| 128 | `releaseAll()` | — |
| 140 | `removeWaiter()` | — |
| 152 | `findFreeDevice()` | 查询 |
| 162 | `deviceOf()` | — |
| 171 | `devices()` | — |
| 175 | `devicesOf()` | — |
| 185 | `waitQueueOf()` | — |
| 189 | `busyCount()` | — |
| 199 | `freeCount()` | — |
| 204 | `hasFreeDevice()` | — |
| 209 | `totalBusy()` | — |
| 220 | `reset()` | — |

### `cn.scau.os.DeviceRequest`　24 行　`src/main/java/cn/scau/os/dev/DeviceRequest.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 21 | `toString()` | — |

### `cn.scau.os.DeviceType`　54 行　`src/main/java/cn/scau/os/dev/DeviceType.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 32 | `getLabel()` | — |
| 37 | `getCount()` | — |
| 42 | `fromChar()` | — |

### `cn.scau.os.EventLog`　79 行　`src/main/java/cn/scau/os/engine/EventLog.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 25 | `CAPACITY` | — |
| 29 | `add()` | — |
| 36 | `system()` | — |
| 40 | `process()` | — |
| 44 | `memory()` | — |
| 48 | `device()` | — |
| 52 | `file()` | — |
| 56 | `error()` | — |
| 61 | `entries()` | — |
| 66 | `recent()` | — |
| 72 | `size()` | — |
| 76 | `clear()` | — |

### `cn.scau.os.LogEntry`　28 行　`src/main/java/cn/scau/os/engine/LogEntry.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 20 | `format()` | — |
| 25 | `toString()` | — |

### `cn.scau.os.LogLevel`　37 行　`src/main/java/cn/scau/os/engine/LogLevel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 34 | `getLabel()` | — |

### `cn.scau.os.OperatingSystem`　665 行　`src/main/java/cn/scau/os/engine/OperatingSystem.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 66 | `DEFAULT_QUANTUM` | — |
| 68 | `DEFAULT_TICK_INTERVAL_MS` | — |
| 70 | `MIN_ARRIVAL_INTERVAL` | — |
| 72 | `MAX_ARRIVAL_INTERVAL` | — |
| 74 | `DISK_IMAGE_NAME` | — |
| 92 | `OperatingSystem()` | — |
| 118 | `tick()` | — |
| 126 | `stepOnce()` | — |
| 131 | `runTicks()` | — |
| 137 | `tickInternal()` | — |
| 180 | `executeOneInstruction()` | CPU 指令执行 |
| 214 | `handleDeviceRequest()` | 设备申请指令的处理 |
| 235 | `handleProgramEnd()` | — |
| 244 | `handleTimeSliceInterrupt()` | — |
| 261 | `handleDeviceCountdown()` | 设备倒计时 |
| 281 | `handDeviceToNextWaiter()` | 把刚释放的设备直接交给等待队列队首 |
| 304 | `dispatchNext()` | 调度 |
| 319 | `handleRandomArrival()` | 随机进程到达 |
| 348 | `randomArrivalInterval()` | — |
| 362 | `startProcess()` | 用指定的可执行文件创建一个进程 |
| 393 | `killProcess()` | — |
| 413 | `blockProcess()` | — |
| 435 | `resumeProcess()` | — |
| 453 | `resetSystem()` | — |
| 469 | `formatDisk()` | — |
| 487 | `availablePrograms()` | — |
| 503 | `tree()` | — |
| 507 | `getFileSystem()` | — |
| 511 | `getMemory()` | — |
| 515 | `getDevices()` | — |
| 519 | `getProcesses()` | — |
| 523 | `getCpu()` | — |
| 527 | `getLog()` | — |
| 531 | `getClock()` | — |
| 535 | `getWorkDirectory()` | — |
| 539 | `isPaused()` | — |
| 543 | `setPaused()` | — |
| 548 | `isAutoArrival()` | — |
| 552 | `setAutoArrival()` | — |
| 557 | `getQuantum()` | — |
| 561 | `setQuantum()` | — |
| 569 | `getTickIntervalMs()` | — |
| 573 | `setTickIntervalMs()` | — |
| 580 | `getArrivalCountdown()` | — |
| 585 | `logFile()` | — |
| 594 | `snapshot()` | — |
| 662 | `close()` | — |

### `cn.scau.os.SystemSnapshot`　123 行　`src/main/java/cn/scau/os/engine/SystemSnapshot.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 80 | `PcbView()` | — |
| 104 | `DeviceView()` | — |

### `cn.scau.os.AssemblyException`　26 行　`src/main/java/cn/scau/os/exec/AssemblyException.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 18 | `AssemblyException()` | — |
| 23 | `getLineNumber()` | — |

### `cn.scau.os.Cpu`　158 行　`src/main/java/cn/scau/os/exec/Cpu.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 39 | `X_MIN` | — |
| 41 | `X_MAX` | — |
| 47 | `getPc()` | — |
| 51 | `getX()` | — |
| 55 | `setX()` | — |
| 60 | `getIr()` | — |
| 65 | `reset()` | — |
| 72 | `restoreFrom()` | — |
| 79 | `saveTo()` | — |
| 94 | `executeStep()` | 执行一条指令 |
| 155 | `registers()` | — |

### `cn.scau.os.CpuOutcome`　98 行　`src/main/java/cn/scau/os/exec/CpuOutcome.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 41 | `CpuOutcome()` | — |
| 51 | `valueUpdated()` | — |
| 55 | `deviceRequest()` | — |
| 59 | `end()` | — |
| 63 | `invalid()` | — |
| 67 | `getKind()` | — |
| 72 | `getInstruction()` | — |
| 76 | `getDeviceType()` | — |
| 80 | `getDuration()` | — |
| 85 | `getXValue()` | — |
| 90 | `getNote()` | — |
| 95 | `toString()` | — |

### `cn.scau.os.InstructionCodec`　312 行　`src/main/java/cn/scau/os/exec/InstructionCodec.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 46 | `ASSIGN_MAX` | — |
| 48 | `CODE_INCREMENT` | — |
| 50 | `CODE_DECREMENT` | — |
| 52 | `CODE_END` | — |
| 54 | `CODE_DEVICE_BASE` | — |
| 56 | `CODE_DEVICE_MAX` | — |
| 59 | `CATEGORY_A` | — |
| 60 | `CATEGORY_B` | — |
| 61 | `CATEGORY_C` | — |
| 64 | `MAX_DEVICE_DURATION` | — |
| 66 | `MAX_ASSIGN_VALUE` | — |
| 68 | `InstructionCodec()` | — |
| 76 | `encodeAssign()` | — |
| 84 | `encodeDevice()` | — |
| 93 | `categoryOf()` | — |
| 107 | `deviceOfCategory()` | — |
| 129 | `decode()` | 解码一个字节 |
| 174 | `assemble()` | 把汇编文本编译成指令字节数组 |
| 191 | `assembleLine()` | — |
| 238 | `disassemble()` | — |
| 250 | `disassembleToText()` | — |

### `cn.scau.os.Main`　70 行　`src/main/java/cn/scau/os/Main.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 35 | `Main()` | — |
| 38 | `main()` | — |
| 60 | `resolveWorkDirectory()` | 决定工作目录：命令行参数 > 系统属性 scauos.home > 当前目录下的 data |

### `cn.scau.os.MemoryManager`　221 行　`src/main/java/cn/scau/os/mem/MemoryManager.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 36 | `USER_MEMORY_SIZE` | — |
| 38 | `MAX_PROCESS` | — |
| 43 | `MemoryManager()` | — |
| 48 | `reset()` | — |
| 65 | `allocate()` | 首次适配分配 |
| 99 | `release()` | — |
| 113 | `mergeFreePartitions()` | — |
| 127 | `loadProgram()` | — |
| 141 | `read()` | — |
| 147 | `write()` | — |
| 152 | `checkAddress()` | — |
| 163 | `partitionOf()` | — |
| 173 | `partitions()` | — |
| 182 | `usedBytes()` | — |
| 193 | `freeBytes()` | — |
| 198 | `largestFreeBlock()` | — |
| 209 | `partitionCount()` | — |
| 214 | `hexDump()` | — |

### `cn.scau.os.MemoryPartition`　76 行　`src/main/java/cn/scau/os/mem/MemoryPartition.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 20 | `FREE_OWNER` | — |
| 28 | `MemoryPartition()` | — |
| 35 | `getStart()` | — |
| 39 | `getSize()` | — |
| 43 | `setSize()` | — |
| 47 | `getEnd()` | — |
| 51 | `getOwnerPid()` | — |
| 55 | `setOwnerPid()` | — |
| 59 | `isFree()` | — |
| 63 | `isLoaded()` | — |
| 67 | `setLoaded()` | — |
| 72 | `toString()` | — |

### `cn.scau.os.Pcb`　213 行　`src/main/java/cn/scau/os/proc/Pcb.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 33 | `IDLE_PID` | — |
| 61 | `Pcb()` | — |
| 72 | `getPid()` | 基本访问器 |
| 76 | `getProgramPath()` | — |
| 80 | `setProgramPath()` | — |
| 84 | `getState()` | — |
| 88 | `setState()` | — |
| 92 | `getPc()` | — |
| 96 | `setPc()` | — |
| 100 | `getX()` | — |
| 104 | `setX()` | — |
| 108 | `getMemoryStart()` | — |
| 112 | `setMemoryStart()` | — |
| 116 | `getMemorySize()` | — |
| 120 | `setMemorySize()` | — |
| 124 | `getDeviceType()` | — |
| 128 | `setDeviceType()` | — |
| 132 | `getSubmitTime()` | — |
| 136 | `setSubmitTime()` | — |
| 140 | `getRunTime()` | — |
| 144 | `setRunTime()` | — |
| 148 | `addRunTime()` | — |
| 152 | `getSliceRemaining()` | — |
| 156 | `setSliceRemaining()` | — |
| 160 | `getBlockedReason()` | — |
| 164 | `setBlockedReason()` | — |
| 168 | `getFinishTime()` | — |
| 172 | `setFinishTime()` | — |
| 176 | `getCurrentInstruction()` | — |
| 180 | `setCurrentInstruction()` | — |
| 184 | `getLastResult()` | — |
| 188 | `setLastResult()` | — |
| 192 | `isIdle()` | — |
| 196 | `isWaitingForMemory()` | — |
| 200 | `setWaitingForMemory()` | — |
| 205 | `displayName()` | — |
| 210 | `toString()` | — |

### `cn.scau.os.ProcessManager`　398 行　`src/main/java/cn/scau/os/proc/ProcessManager.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 51 | `MAX_PROCESS` | — |
| 69 | `ProcessManager()` | — |
| 92 | `createProcess()` | 创建一个进程并尝试装入内存 |
| 129 | `retryPendingProcesses()` | 为等内存的进程重试分配 |
| 162 | `destroy()` | 撤销进程：回收内存 → 释放设备 → 从所有队列摘除 → 释放 PCB |
| 192 | `block()` | 阻塞当前进程。CPU 现场由调用方（CPU）先保存到 PCB，这里只负责状态与队列 |
| 212 | `wake()` | 唤醒进程：从阻塞队列摘下 → 置为就绪 → 挂到就绪队列尾部 |
| 232 | `dispatch()` | 从就绪队列取一个进程投入运行；就绪队列为空时运行闲逛进程 |
| 250 | `preemptRunning()` | 时间片用完，把运行中的进程放回就绪队列尾部 |
| 266 | `getRunning()` | 查询 |
| 270 | `getIdleProcess()` | — |
| 274 | `getQuantum()` | — |
| 278 | `setQuantum()` | — |
| 286 | `readyQueue()` | — |
| 290 | `readyCount()` | — |
| 294 | `hasReadyProcess()` | — |
| 299 | `blockedQueue()` | — |
| 304 | `pendingQueue()` | — |
| 309 | `finishedQueue()` | — |
| 314 | `activeProcesses()` | — |
| 324 | `find()` | — |
| 341 | `activeCount()` | — |
| 351 | `freeSlotCount()` | — |
| 356 | `reset()` | — |
| 375 | `findFreeSlot()` | 内部工具 |
| 384 | `freeSlot()` | — |
| 393 | `removeFromQueues()` | — |

### `cn.scau.os.ProcessState`　43 行　`src/main/java/cn/scau/os/proc/ProcessState.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 35 | `getLabel()` | — |
| 40 | `toString()` | — |

### `cn.scau.os.ProgramLoader`　27 行　`src/main/java/cn/scau/os/proc/ProgramLoader.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |

### `cn.scau.os.TextUtil`　91 行　`src/main/java/cn/scau/os/util/TextUtil.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 20 | `TextUtil()` | — |
| 24 | `width()` | — |
| 35 | `isWide()` | — |
| 46 | `pad()` | — |
| 60 | `padLeft()` | — |
| 74 | `hex()` | — |

---

## 成员 B · 文件系统与磁盘存储　权重 0.20

### `cn.scau.os.DefaultFileTree`　161 行　`src/main/java/cn/scau/os/fs/DefaultFileTree.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 39 | `DefaultFileTree()` | — |
| 43 | `install()` | — |
| 52 | `createDirectories()` | — |
| 64 | `createPrograms()` | 10 个可执行程序，覆盖：纯赋值、自增自减、时间片切换（>6 条指令）、 |
| 96 | `createDocuments()` | — |
| 120 | `createDataFiles()` | — |
| 126 | `createSystemFiles()` | — |
| 133 | `createRootFiles()` | — |
| 150 | `addProgram()` | 把一个汇编程序编译成机器码写入可执行文件 |
| 158 | `utf8()` | — |

### `cn.scau.os.DirEntry`　231 行　`src/main/java/cn/scau/os/fs/DirEntry.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 33 | `SIZE` | — |
| 35 | `NAME_LENGTH` | — |
| 37 | `ENTRIES_PER_BLOCK` | — |
| 45 | `DirEntry()` | — |
| 68 | `encode()` | 把本对象写入 buffer 的 offset 处（8 字节） |
| 81 | `decode()` | — |
| 107 | `rootEntry()` | 根目录的"合成"目录项 |
| 118 | `isEmptySlot()` | — |
| 123 | `clearSlot()` | — |
| 137 | `normalizeName()` | 规范化名称：转大写，并校验长度与字符集 |
| 156 | `normalizeExtension()` | — |
| 172 | `getName()` | 访问器 |
| 176 | `getExtension()` | — |
| 180 | `getAttribute()` | — |
| 184 | `setAttribute()` | — |
| 188 | `getStartBlock()` | — |
| 192 | `setStartBlock()` | — |
| 196 | `getLength()` | — |
| 200 | `setLength()` | — |
| 204 | `isDirectory()` | — |
| 214 | `getFullName()` | 带扩展名的完整名称，例如 "P01.E"、"DOC" |
| 219 | `blockCount()` | — |
| 223 | `copy()` | — |
| 228 | `toString()` | — |

### `cn.scau.os.DirNode`　75 行　`src/main/java/cn/scau/os/fs/DirNode.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 30 | `DirNode()` | — |
| 39 | `addChild()` | — |
| 43 | `getName()` | — |
| 47 | `getPath()` | — |
| 51 | `isDirectory()` | — |
| 55 | `getAttribute()` | — |
| 59 | `getLength()` | — |
| 63 | `getStartBlock()` | — |
| 67 | `getChildren()` | — |
| 72 | `toString()` | — |

### `cn.scau.os.Disk`　189 行　`src/main/java/cn/scau/os/fs/Disk.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 39 | `BLOCK_SIZE` | — |
| 41 | `BLOCK_COUNT` | — |
| 43 | `TOTAL_BYTES` | — |
| 52 | `Disk()` | 打开（不存在则创建）磁盘镜像文件 |
| 69 | `resizeToSpec()` | — |
| 80 | `readBlock()` | 读取一个物理块，返回长度为 #BLOCK_SIZE 的字节数组 |
| 95 | `writeBlock()` | 写入一个物理块。data 不足 64 字节时用 0 补齐，超出的部分被忽略 |
| 110 | `clearBlock()` | — |
| 115 | `clear()` | — |
| 126 | `flush()` | — |
| 134 | `getImagePath()` | — |
| 139 | `imageSize()` | — |
| 148 | `isEmptyImage()` | — |
| 160 | `checkBlockNo()` | — |
| 167 | `close()` | — |
| 178 | `toString()` | — |
| 183 | `deleteImage()` | — |

### `cn.scau.os.Fat`　358 行　`src/main/java/cn/scau/os/fs/Fat.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 50 | `FREE` | — |
| 52 | `END_OF_CHAIN` | — |
| 54 | `RESERVED` | — |
| 57 | `ENTRY_COUNT` | — |
| 60 | `FAT_BLOCK_HEAD` | — |
| 62 | `ROOT_DIR_BLOCK` | — |
| 64 | `FAT_BLOCK_TAIL` | — |
| 66 | `FIRST_DATA_BLOCK` | — |
| 68 | `LAST_DATA_BLOCK` | — |
| 70 | `DATA_BLOCK_COUNT` | — |
| 73 | `ENTRIES_PER_BLOCK` | — |
| 91 | `isReservedBlock()` | 该块是否属于系统保留区（FAT 自身、根目录、末块） |
| 104 | `Fat()` | — |
| 113 | `blockOfEntry()` | — |
| 121 | `offsetOfEntry()` | — |
| 129 | `isFatBlock()` | — |
| 138 | `load()` | — |
| 146 | `flush()` | — |
| 159 | `isFormatted()` | — |
| 171 | `reset()` | 建立空的 FAT：所有数据块空闲，FAT 自身、根目录块和末块标记为保留 |
| 185 | `get()` | 基本读写 |
| 189 | `set()` | — |
| 193 | `isFree()` | — |
| 209 | `allocateChain()` | 申请一段块链 |
| 235 | `freeChain()` | — |
| 259 | `chainOf()` | 把一条块链展开成块号列表（首块在前）。遇到坏链时提前结束， |
| 278 | `appendBlock()` | — |
| 299 | `chainLength()` | — |
| 304 | `freeBlockCount()` | — |
| 315 | `usedBlockCount()` | — |
| 320 | `snapshot()` | — |
| 327 | `kindOf()` | — |

### `cn.scau.os.FileAttribute`　104 行　`src/main/java/cn/scau/os/fs/FileAttribute.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 22 | `READ_ONLY` | — |
| 24 | `SYSTEM` | — |
| 26 | `DIRECTORY` | — |
| 28 | `EXECUTABLE` | — |
| 31 | `NORMAL` | — |
| 33 | `FileAttribute()` | — |
| 36 | `isReadOnly()` | — |
| 40 | `isSystem()` | — |
| 44 | `isDirectory()` | — |
| 48 | `isExecutable()` | — |
| 53 | `describe()` | — |
| 75 | `parse()` | 解析用户输入的属性名 |
| 101 | `sanitizeForFile()` | — |

### `cn.scau.os.FileSystem`　936 行　`src/main/java/cn/scau/os/fs/FileSystem.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 50 | `FileSystem()` | — |
| 62 | `open()` | 打开磁盘镜像。若镜像尚未格式化，则格式化并安装默认文件树 |
| 75 | `createFresh()` | — |
| 88 | `getDisk()` | — |
| 92 | `getFat()` | — |
| 96 | `getOpenFiles()` | — |
| 108 | `format()` | 格式化：清空全部磁盘块，重建 FAT 与空根目录，并关闭所有已打开文件 |
| 117 | `isFormatted()` | — |
| 125 | `exists()` | 查询 |
| 133 | `isDirectory()` | — |
| 143 | `stat()` | — |
| 153 | `list()` | — |
| 168 | `directoryBlockCount()` | — |
| 173 | `directoryCapacity()` | — |
| 182 | `mkdir()` | — |
| 211 | `rmdir()` | 删除空目录。目录非空时报错（这是 rmdir 与 deldir 的区别） |
| 228 | `deldir()` | — |
| 232 | `deleteDirectoryRecursive()` | — |
| 266 | `createFile()` | — |
| 300 | `readFile()` | — |
| 309 | `readText()` | — |
| 317 | `writeFile()` | 整文件覆盖写入（相当于"保存"）。会先申请新块再释放旧块， |
| 337 | `writeText()` | — |
| 342 | `deleteFile()` | — |
| 363 | `copyFile()` | 复制文件 |
| 401 | `moveFile()` | 移动文件（同一目录内改名，或跨目录移动） |
| 444 | `changeAttribute()` | — |
| 459 | `openFile()` | — |
| 475 | `closeFile()` | — |
| 493 | `readOpenedFile()` | 从读指针处读取最多 count 个字节，返回实际读到的内容 |
| 526 | `writeOpenedFile()` | — |
| 569 | `freeBlocks()` | 统计与可视化数据 |
| 573 | `usedBlocks()` | — |
| 577 | `fatSnapshot()` | — |
| 585 | `blockOwners()` | 每个磁盘块属于谁：、、某个文件路径，或空串表示空闲 |
| 600 | `markOwners()` | — |
| 625 | `buildTree()` | 把整棵目录树读成 DirNode，供界面显示 |
| 631 | `fillTree()` | — |
| 652 | `ownerOfBlock()` | — |
| 664 | `installDefaultTree()` | — |
| 699 | `directoryChain()` | 目录占用的物理块列表 |
| 709 | `scanDirectory()` | — |
| 729 | `countEntries()` | — |
| 739 | `rootEntryCount()` | — |
| 744 | `directoryStartBlock()` | — |
| 760 | `lookup()` | — |
| 794 | `findEntry()` | — |
| 805 | `requireLocated()` | — |
| 815 | `addEntry()` | — |
| 840 | `writeEntry()` | — |
| 852 | `removeEntry()` | — |
| 864 | `removeEntryByPath()` | — |
| 873 | `writeNewChain()` | — |
| 894 | `readChain()` | — |
| 914 | `resolveCopyTarget()` | 计算 copy / move 的真正目标路径 |
| 928 | `close()` | — |
| 933 | `openFileCount()` | — |

### `cn.scau.os.FsException`　27 行　`src/main/java/cn/scau/os/fs/FsException.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 20 | `FsException()` | — |

### `cn.scau.os.OpenFile`　102 行　`src/main/java/cn/scau/os/fs/OpenFile.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 22 | `MODE_READ` | — |
| 24 | `MODE_WRITE` | — |
| 34 | `OpenFile()` | — |
| 44 | `getPath()` | — |
| 48 | `getAttribute()` | — |
| 52 | `getStartBlock()` | — |
| 56 | `setStartBlock()` | — |
| 60 | `getLength()` | — |
| 64 | `setLength()` | — |
| 68 | `getMode()` | — |
| 72 | `isWritable()` | — |
| 76 | `getReadPointer()` | — |
| 80 | `setReadPointer()` | — |
| 84 | `getWritePointer()` | — |
| 88 | `setWritePointer()` | — |
| 93 | `isAtEnd()` | — |
| 98 | `toString()` | — |

### `cn.scau.os.OpenFileTable`　94 行　`src/main/java/cn/scau/os/fs/OpenFileTable.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 23 | `MAX_OPEN_FILES` | — |
| 28 | `find()` | — |
| 37 | `isOpen()` | — |
| 42 | `allocate()` | — |
| 56 | `close()` | — |
| 68 | `closeAll()` | — |
| 75 | `list()` | — |
| 85 | `count()` | — |

### `cn.scau.os.PathUtil`　155 行　`src/main/java/cn/scau/os/fs/PathUtil.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 31 | `ROOT` | — |
| 33 | `PathUtil()` | — |
| 37 | `normalize()` | — |
| 72 | `resolve()` | 把可能是相对路径的输入解析成绝对路径 |
| 85 | `segments()` | — |
| 100 | `parent()` | — |
| 110 | `fileName()` | — |
| 117 | `isRoot()` | — |
| 122 | `join()` | — |
| 131 | `splitName()` | 把 "P01.E" 拆成 名称 "P01" + 扩展名 'e' |

---

## 成员 C · 命令层与界面框架　权重 0.20

### `cn.scau.os.CommandResult`　57 行　`src/main/java/cn/scau/os/cmd/CommandResult.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 22 | `CommandResult()` | — |
| 28 | `ok()` | — |
| 32 | `error()` | — |
| 37 | `clear()` | — |
| 41 | `isSuccess()` | — |
| 45 | `getMessage()` | — |
| 49 | `isClearScreen()` | — |
| 54 | `hasOutput()` | — |

### `cn.scau.os.ConfirmService`　25 行　`src/main/java/cn/scau/os/cmd/ConfirmService.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |

### `cn.scau.os.EditorService`　25 行　`src/main/java/cn/scau/os/cmd/EditorService.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |

### `cn.scau.os.Shell`　589 行　`src/main/java/cn/scau/os/cmd/Shell.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 50 | `Shell()` | — |
| 56 | `setEditor()` | — |
| 61 | `setConfirm()` | — |
| 65 | `getCurrentDir()` | — |
| 70 | `getPrompt()` | — |
| 75 | `resolvePath()` | — |
| 88 | `execute()` | 执行一行命令 |
| 112 | `dispatch()` | — |
| 167 | `cmdHelp()` | 帮助 |
| 200 | `helpFor()` | — |
| 247 | `cmdDir()` | 目录浏览 |
| 259 | `listDirectory()` | — |
| 292 | `blockCountOf()` | — |
| 299 | `formatFileLine()` | — |
| 306 | `cmdLs()` | — |
| 317 | `cmdType()` | — |
| 352 | `cmdCreate()` | 文件操作 |
| 360 | `cmdDelete()` | — |
| 368 | `cmdCopy()` | — |
| 377 | `cmdMove()` | — |
| 386 | `cmdMkdir()` | — |
| 394 | `cmdRmdir()` | — |
| 402 | `cmdDeldir()` | — |
| 410 | `cmdChange()` | — |
| 419 | `cmdChdir()` | — |
| 431 | `cmdEdit()` | — |
| 457 | `cmdRun()` | 进程与系统 |
| 465 | `cmdFormat()` | — |
| 481 | `cmdFdisk()` | — |
| 503 | `cmdPs()` | — |
| 540 | `cmdOpen()` | 已打开文件表 |
| 553 | `cmdClose()` | — |
| 561 | `cmdRead()` | — |
| 584 | `requireArgs()` | 工具 |

### `cn.scau.os.CardPanel`　86 行　`src/main/java/cn/scau/os/ui/CardPanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 33 | `CardPanel()` | — |
| 42 | `setBadge()` | — |
| 48 | `paintComponent()` | — |

### `cn.scau.os.ControlPanel`　275 行　`src/main/java/cn/scau/os/ui/ControlPanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 67 | `ControlPanel()` | — |
| 90 | `buildFirstRow()` | 布局 |
| 115 | `buildSecondRow()` | — |
| 143 | `titleBlock()` | — |
| 159 | `separator()` | — |
| 169 | `metric()` | — |
| 188 | `wireActions()` | 事件 |
| 246 | `update()` | — |

### `cn.scau.os.FlatButton`　76 行　`src/main/java/cn/scau/os/ui/FlatButton.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 30 | `FlatButton()` | — |
| 58 | `paintComponent()` | — |

### `cn.scau.os.LogPanel`　122 行　`src/main/java/cn/scau/os/ui/LogPanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 41 | `LogPanel()` | — |
| 61 | `update()` | — |
| 77 | `setAutoScroll()` | — |

### `cn.scau.os.MainFrame`　337 行　`src/main/java/cn/scau/os/ui/MainFrame.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 66 | `MainFrame()` | — |
| 119 | `buildCenter()` | — |
| 153 | `buildBottom()` | — |
| 163 | `buildMenu()` | — |
| 197 | `menuItem()` | — |
| 206 | `installShortcuts()` | — |
| 234 | `refresh()` | — |
| 245 | `refreshNow()` | — |
| 250 | `refreshAndReload()` | — |
| 259 | `formatDisk()` | 菜单动作 |
| 272 | `showHelp()` | — |
| 283 | `showAbout()` | — |
| 296 | `shutdown()` | — |
| 315 | `applyDefaultDividers()` | 按比例设置分隔条位置 |
| 323 | `launch()` | — |
| 333 | `getShell()` | — |

### `cn.scau.os.SimulationDriver`　75 行　`src/main/java/cn/scau/os/ui/SimulationDriver.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 33 | `SimulationDriver()` | — |
| 44 | `start()` | — |
| 52 | `stop()` | — |
| 59 | `isRunning()` | — |
| 64 | `setIntervalMs()` | — |
| 71 | `step()` | — |

### `cn.scau.os.SnapshotAware`　21 行　`src/main/java/cn/scau/os/ui/SnapshotAware.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |

### `cn.scau.os.Theme`　241 行　`src/main/java/cn/scau/os/ui/Theme.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 30 | `BACKGROUND` | ---- 基础色 ---- |
| 31 | `CARD` | — |
| 32 | `BORDER` | — |
| 33 | `TEXT` | — |
| 34 | `MUTED` | — |
| 35 | `ACCENT` | — |
| 36 | `ACCENT_LIGHT` | — |
| 39 | `SUCCESS` | ---- 语义色 ---- |
| 40 | `WARNING` | — |
| 41 | `DANGER` | — |
| 42 | `PURPLE` | — |
| 43 | `TEAL` | — |
| 46 | `STATE_NEW_BG` | ---- 进程状态色 ---- |
| 47 | `STATE_NEW_FG` | — |
| 48 | `STATE_READY_BG` | — |
| 49 | `STATE_READY_FG` | — |
| 50 | `STATE_RUNNING_BG` | — |
| 51 | `STATE_RUNNING_FG` | — |
| 52 | `STATE_BLOCKED_BG` | — |
| 53 | `STATE_BLOCKED_FG` | — |
| 54 | `STATE_DONE_BG` | — |
| 55 | `STATE_DONE_FG` | — |
| 59 | `PROCESS_COLORS` | — |
| 65 | `BLOCK_FREE` | — |
| 66 | `BLOCK_FAT` | — |
| 67 | `BLOCK_ROOT` | — |
| 68 | `BLOCK_RESERVED` | — |
| 69 | `BLOCK_USED` | — |
| 77 | `UI_FONT_NAME` | 界面字体：优先中文字体，保证中文不出现方框 |
| 81 | `MONO_FONT_NAME` | — |
| 87 | `CONSOLE_FONT_NAME` | 控制台字体：既要等宽又要能显示中文 |
| 90 | `FONT_BASE` | — |
| 91 | `FONT_SMALL` | — |
| 92 | `FONT_BOLD` | — |
| 93 | `FONT_TITLE` | — |
| 94 | `FONT_HUGE` | — |
| 96 | `FONT_MONO` | — |
| 97 | `FONT_MONO_BOLD` | — |
| 99 | `FONT_CONSOLE` | — |
| 101 | `Theme()` | — |
| 109 | `pickFont()` | 挑选系统里真实存在的第一个字体 |
| 125 | `install()` | — |
| 158 | `processColor()` | — |
| 166 | `lighter()` | — |
| 174 | `stateForeground()` | — |
| 190 | `stateBackground()` | — |
| 206 | `cardBorder()` | — |
| 213 | `compact()` | — |
| 220 | `hint()` | — |
| 228 | `value()` | — |
| 236 | `transparent()` | — |

---

## 成员 D · 数据视图与交互面板　权重 0.20

### `cn.scau.os.CommandPanel`　186 行　`src/main/java/cn/scau/os/ui/CommandPanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 52 | `CommandPanel()` | — |
| 103 | `smallButton()` | — |
| 111 | `executeCurrent()` | — |
| 123 | `runCommand()` | — |
| 143 | `print()` | — |
| 159 | `clearOutput()` | — |
| 163 | `recallHistory()` | — |
| 178 | `focusInput()` | — |
| 183 | `refreshPrompt()` | — |

### `cn.scau.os.DevicePanel`　153 行　`src/main/java/cn/scau/os/ui/DevicePanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 42 | `DevicePanel()` | — |
| 60 | `update()` | — |

### `cn.scau.os.DiskGridPanel`　259 行　`src/main/java/cn/scau/os/ui/DiskGridPanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 53 | `DiskGridPanel()` | — |
| 80 | `buildLegend()` | — |
| 91 | `legendItem()` | — |
| 114 | `update()` | — |
| 128 | `showDetail()` | 显示"第几块"的详细信息。这是全项目最适合讲"FAT 到底是什么"的地方 |
| 153 | `kindName()` | — |

### `cn.scau.os.EditorDialog`　155 行　`src/main/java/cn/scau/os/ui/EditorDialog.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 51 | `EditorDialog()` | — |
| 102 | `open()` | 打开编辑器 |
| 108 | `loadText()` | — |
| 116 | `hintText()` | — |
| 123 | `doSave()` | — |

### `cn.scau.os.FileSystemPanel`　524 行　`src/main/java/cn/scau/os/ui/FileSystemPanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 80 | `FileSystemPanel()` | — |
| 140 | `buildToolbar()` | — |
| 152 | `button()` | — |
| 164 | `update()` | 树刷新 |
| 178 | `signatureOf()` | — |
| 184 | `appendSignature()` | — |
| 192 | `rebuildTree()` | — |
| 212 | `buildNode()` | — |
| 220 | `collectExpandedPaths()` | — |
| 236 | `restoreExpanded()` | — |
| 249 | `selectPath()` | — |
| 264 | `selectedNode()` | — |
| 272 | `selectedDir()` | — |
| 284 | `showPreview()` | 预览 |
| 341 | `createFile()` | 操作 |
| 350 | `createDirectory()` | — |
| 359 | `deleteSelected()` | — |
| 375 | `copySelected()` | — |
| 389 | `editSelected()` | — |
| 398 | `runSelected()` | — |
| 411 | `join()` | — |
| 415 | `run()` | — |
| 431 | `firstLine()` | — |
| 439 | `warn()` | — |
| 443 | `maybePopup()` | — |
| 473 | `menuItem()` | — |
| 479 | `setAttribute()` | — |

### `cn.scau.os.MemoryPanel`　199 行　`src/main/java/cn/scau/os/ui/MemoryPanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 50 | `MemoryPanel()` | — |
| 79 | `update()` | — |

### `cn.scau.os.ProcessPanel`　352 行　`src/main/java/cn/scau/os/ui/ProcessPanel.java`

| 行号 | 成员 | 一句话说明 |
| --- | --- | --- |
| 64 | `ProcessPanel()` | — |
| 98 | `configureTable()` | — |
| 134 | `maybeShowPopup()` | — |
| 180 | `warn()` | — |
| 185 | `update()` | — |
| 225 | `joinNames()` | — |
| 236 | `chip()` | — |
| 246 | `queueLabel()` | — |

---

## 附：测试代码索引

| 测试类 | 行数 | 负责成员 | 路径 |
| --- | --- | --- | --- |
| `ShellTest` | 244 | C | `src/test/java/cn/scau/os/cmd/ShellTest.java` |
| `DeviceManagerTest` | 177 | A | `src/test/java/cn/scau/os/dev/DeviceManagerTest.java` |
| `IntegratedScenarioTest` | 219 | A | `src/test/java/cn/scau/os/engine/IntegratedScenarioTest.java` |
| `InstructionCodecTest` | 147 | A | `src/test/java/cn/scau/os/exec/InstructionCodecTest.java` |
| `FileSystemTest` | 407 | B | `src/test/java/cn/scau/os/fs/FileSystemTest.java` |
| `MemoryManagerTest` | 179 | A | `src/test/java/cn/scau/os/mem/MemoryManagerTest.java` |
| `ProcessSchedulingTest` | 253 | A | `src/test/java/cn/scau/os/proc/ProcessSchedulingTest.java` |
