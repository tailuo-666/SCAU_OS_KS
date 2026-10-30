# 存储管理与设备管理

这两个模块都比文件系统小，但都有"细节决定正确性"的地方：
内存的**分区拆分与合并**、设备的**倒计时与等待队列交接**。

---

# 第一部分：存储管理

## 1. 内存模型

```
主存（模拟）
├── 系统区（不在模拟范围内，界面上不画）
└── 用户区：512 字节，地址 0 ~ 511
     ├── 进程 P1 的程序副本（每个进程占一段连续区间）
     ├── 进程 P2 的程序副本
     └── 空闲区
```

- 用户区就是一个 `byte[512]`（`MemoryManager.userArea`）；
- 进程装入的是**指令字节**，1 条指令 1 字节，所以"程序 8 条指令"就占 8 字节；
- 分区表是"按地址有序的 List"（`List<MemoryPartition>`），
  用 `ownerPid == -1` 表示空闲分区。

`MemoryPartition` 只有四个字段：起始地址、长度、归属 PID、是否已装入程序。

## 2. 首次适配分配

```java
public int allocate(int pid, int size)
```

算法（真正的代码就只有十几行）：

```
for 每个分区（按地址从低到高）:
    if 分区空闲 且 分区长度 >= size:
        切走前 size 字节给 pid
        如果还有剩余: 剩余部分作为新空闲分区插到它后面
        return 起始地址
return -1   // 装不下
```

**"首次适配"的含义**：不找最小的合适分区（最佳适配），也不找最大的（最差适配），
而是**从低地址开始遇到的第一个够大的就用**。好处是实现简单、回收快；
代价是低地址容易留下很多小碎片。

`allocate` 的三个额外检查：

1. `size <= 0` → 按 1 字节处理（空程序也要占地方）；
2. `size > 512` → 直接返回 -1；
3. 同一 PID 重复申请 → 抛 `IllegalStateException`（防止一个进程占两段内存）。

## 3. 回收与合并

```java
public void release(int pid)
```

```
找到 pid 的分区 → 清零这段内存 → 标记为空闲 → 合并所有相邻空闲区
```

`mergeFreePartitions()` 从头扫一遍分区表：相邻两块都空闲就合成一块，长度相加。
**必须合并**，否则反复创建/撤销进程之后，512 字节会被切成一堆 2~8 字节的碎片，
明明总量够却分配不出 100 字节。

### 一个具体例子

| 步骤 | 操作 | 分区表 | 空闲 / 已用 |
|---|---|---|---|
| 初始 | — | `[0,512) 空闲` | 512 / 0 |
| 1 | `allocate(P1,100)` | `[0,100) P1`、`[100,512) 空闲` | 412 / 100 |
| 2 | `allocate(P2,100)` | `[0,100) P1`、`[100,200) P2`、`[200,512) 空闲` | 312 / 200 |
| 3 | `allocate(P3,50)` | `[0,100) P1`、`[100,200) P2`、`[200,250) P3`、`[250,512) 空闲` | 262 / 250 |
| 4 | `release(P2)` | `[0,100) P1`、`[100,200) 空闲`、`[200,250) P3`、`[250,512) 空闲` | 362 / 150 |
| 5 | `release(P1)` | `[0,200) 空闲`、`[200,250) P3`、`[250,512) 空闲` | 462 / 50 |
| 6 | `release(P3)` | `[0,512) 空闲` | 512 / 0 |

第 4 步 P2 释放后**没有**立刻合并（左右邻居都有人），第 5 步 P1 释放才和 P2 的洞合并，
这就是"只有相邻空闲区才合并"。

对应的断言在 `MemoryManagerTest.releaseMergesNeighbours`。

## 4. 装入程序与执行

```java
int start = memory.allocate(pid, code.length);   // 分到一段
memory.loadProgram(start, code);                 // 从 start 开始写入指令字节
int byteCode = memory.read(start + pc);          // CPU 取指
```

CPU 取指用的是"内存起始地址 + PC 偏移"，不是磁盘文件。
所以：

- 进程运行中删除源文件，不影响它继续执行（`IntegratedScenarioTest` 场景二）；
- 一个进程的 PC 从 0 开始，不会因为别人占用了别的区间而错位。

`loadProgram` 会把该分区标记为 `loaded = true`，界面据此用不同颜色区分
"放了程序的已用块"和"仅仅被占用的块"。

## 5. 内存不足的处理

`allocate` 返回 -1 时，`ProcessManager.createProcess` **不报错**，而是：

```java
pcb.setState(NEW);
pcb.setWaitingForMemory(true);
pcb.setBlockedReason("等待内存（需要 xxxB）");
pendingQueue.add(pcb);
```

之后每个 tick 的第 ⑥ 步 `retryPendingProcesses()` 会再试一次；
某个进程结束释放内存后，排在前面的等内存进程就会被装入并转为 READY
（`PC`、`X` 复位为 0，从头开始执行）。

这条设计让"程序比空闲内存还大"变成一个可观察的状态（等内存队列），
而不是一个异常——答辩演示时比较容易讲清楚。

## 6. 上限与统计

| 指标 | 方法 | 说明 |
|---|---|---|
| 用户区大小 | `USER_MEMORY_SIZE = 512` | 固定 |
| 最多进程 | `MAX_PROCESS = 10` | PCB 池大小，与内存大小无关（程序都很小） |
| 已用 | `usedBytes()` | 所有非空闲分区长度之和 |
| 空闲 | `freeBytes()` | 所有空闲分区长度之和 |
| 最大空闲块 | `largestFreeBlock()` | 判断"还能不能装下某个程序"最直接 |
| 分区数 | `partitionCount()` | 界面显示碎片程度 |

`usedBytes() + freeBytes() == 512` 永远成立（`MemoryManagerTest` 里间接验证）。

---

# 第二部分：设备管理

## 1. 设备模型

| 类型 | 数量 | 编号 |
|---|---|---|
| A | 2 | A1、A2 |
| B | 3 | B1、B2、B3 |
| C | 3 | C1、C2、C3 |
| 合计 | 8 | |

全部是**独占设备**：一台设备同一时刻只能被一个进程占用，
用完后必须释放，别人才能用。

`Device` 只有三个状态量：`ownerPid`（-1 表示空闲）、`remaining`（剩余时间单位）、
类型与编号。

## 2. 申请流程

```java
if (devices.request(type, pid, duration)) {
    // 有空闲实例：已分配，remaining = duration
} else {
    devices.enqueue(type, pid, duration);   // 进该类型的等待队列（FIFO）
}
```

两种情况下进程都进入**阻塞**状态（设备是独占的，用的时候不能同时算题）：

```java
processes.block(pcb, "占用A类设备（剩 3）" / "等待A类设备", type);
```

区别只在阻塞原因不同，界面上能直接看出"是在用设备"还是"在等设备"。

## 3. 倒计时与 I/O 中断

设备的时间推进**只有一个入口**：

```java
List<Device> finished = devices.tickDevices();   // 所有占用中的设备 remaining -1
```

返回"本次刚好归零"的设备。`OperatingSystem.handleDeviceCountdown()` 接着做三件事：

```
① 释放设备（ownerPid = -1, remaining = 0）
② 唤醒原进程：blocked → ready（放就绪队列尾部）
③ 把设备直接交给等待队列队首（如果有）
```

第 ③ 步是"设备不空转"的优化：A1 刚空出来，排在队首的 P3 立刻拿到它，
`remaining` 按 P3 当初申请的时长重新开始，并且 P3 的阻塞原因改成"占用A类设备（剩 3）"。
P3 仍然处于 BLOCKED，等这台设备再用完才被唤醒。

**为什么在 tick 的第 ② 步做**：必须在执行指令之前扣倒计时，否则
"P1 在 t2 申请的设备"会在 t2 就被扣掉一次，实际少用了一个时间单位。

## 4. 一个完整的时序表（3 个进程竞争 2 台 A 设备）

程序 P04：`x=20`、`!A3`、`x++`、`end`（申请 A 类设备 3 个时间单位）。

| tick | A1 | A2 | 等待队列 | 事件 |
|---|---|---|---|---|
| 1 | 空闲 | 空闲 | — | P1 执行 `x=20` |
| 2 | **P1 剩 3** | 空闲 | — | P1 申请 → 拿到 A1 → 阻塞 |
| 3 | P1 剩 2 | 空闲 | — | P2 执行 `x=20` |
| 4 | P1 剩 1 | **P2 剩 3** | — | P2 申请 → 拿到 A2 → 阻塞 |
| 5 | P1 剩 0 → **释放，唤醒 P1** | P2 剩 2 | — | P3 执行 `x=20` |
| 6 | **P3 剩 3** | P2 剩 1 | — | P3 申请 → A1 空着，直接拿到 |
| 7 | P3 剩 2 | P2 剩 0 → **释放，唤醒 P2** | — | P1 执行 `x++`（X=21） |
| 8 | P3 剩 1 | 空闲 | — | P1 执行 `end` → 撤销 |
| 9 | P3 剩 0 → **释放，唤醒 P3** | 空闲 | — | P2 执行 `x++`（X=21） |
| 10 | 空闲 | 空闲 | — | P2 `end` → 撤销 |
| 11~12 | 空闲 | 空闲 | — | P3 `x++`、`end` |

结论：这个配置下没有进程真正排队，因为 A1 在 t5 就释放了，
而 P3 到 t6 才申请。**想看到等待队列非空，要么把时长加大（`!A9`），
要么放 4 个以上的进程**——测试 `threeProcessesCompeteForTwoDevices` 用的是 `!A9`。

## 5. 撤销进程时的设备回收

`ProcessManager.destroy` 会调用：

```java
devices.releaseAll(pid);     // 该进程占用的所有设备 → 空闲
devices.removeWaiter(pid);   // 从所有等待队列里摘掉它
```

**`removeWaiter` 不能省**：否则队列里会留下一个已经不存在的 PID，
设备释放时按它分配会失败（"幽灵请求"）。
`handDeviceToNextWaiter` 也做了防御：如果队首进程已经不在阻塞状态，
就立刻收回设备并继续找下一个，不会死循环。

## 6. 手动唤醒的限制

```java
public boolean resumeProcess(int pid)   // OperatingSystem
```

- 只能唤醒"人工阻塞"（`deviceType == null`）的进程；
- 等设备的进程返回 `false`，必须等设备自然完成。

原因：如果强行唤醒一个"以为自己用完设备"的进程，而设备还在别人手里，
它接下来会继续执行后面的指令，状态立刻不一致。宁可拒绝。

## 7. 设备相关的统计

| 方法 | 用途 |
|---|---|
| `busyCount(type)` / `freeCount(type)` | 界面上"A 类 1/2 占用" |
| `totalBusy()` | 顶部状态条 |
| `deviceOf(pid)` | 某进程当前占用的设备（进程表里显示） |
| `waitQueueOf(type)` | 等待队列（界面按 `P3(5)` 显示） |
| `hasFreeDevice(type)` | 快速判断 |

## 8. 与进程模块的职责边界

| 事情 | 谁做 |
|---|---|
| 找一台空闲设备并标记占用 | `DeviceManager` |
| 把设备时间 -1、判断是否归零 | `DeviceManager.tickDevices` |
| 决定"设备空了要不要唤醒谁" | `OperatingSystem` |
| 改 PCB 的状态、原因、所属设备 | `ProcessManager.block/wake` |
| 撤销进程时回收资源 | `ProcessManager.destroy`（调用 `DeviceManager.releaseAll`） |

**`dispatchToNext` 只改设备，不改 PCB**——这条边界让设备模块可以脱离进程独立测试
（`DeviceManagerTest` 里完全没有 PCB）。

## 9. 容易踩的坑

| 坑 | 现象 | 现在的处理 |
|---|---|---|
| 重复分配同一台设备 | 两个进程以为自己在用 A1 | `Device.assign` 抛 `IllegalStateException` |
| 重复释放 | 空闲计数变成 9 | `Device.release` 抛异常 |
| 队列里留下已撤销进程 | 设备分给"幽灵"，之后再没人释放 | `removeWaiter` + `handDeviceToNextWaiter` 跳过 |
| 倒计时扣两次 | 设备提前一个单位释放 | 只有 `tickDevices()` 一个入口，且每 tick 只调一次 |
| 时长 0 | 申请后立刻"用完" | `tick()` 返回 true，下一个时间单位释放（不会卡死） |
| 进程被撤销但设备没释放 | 设备永久占用 | `destroy` 里 `releaseAll` |
