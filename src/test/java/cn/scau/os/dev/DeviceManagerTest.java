package cn.scau.os.dev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备管理模块单元测试：分配表、互斥占用、等待队列、倒计时释放、回收。
 */
@DisplayName("设备管理模块")
class DeviceManagerTest {

    private DeviceManager devices;

    @BeforeEach
    void setUp() {
        devices = new DeviceManager();
    }

    @Test
    @DisplayName("共 8 台设备：A×2、B×3、C×3")
    void deviceCounts() {
        assertEquals(8, devices.devices().size());
        assertEquals(2, devices.devicesOf(DeviceType.A).size());
        assertEquals(3, devices.devicesOf(DeviceType.B).size());
        assertEquals(3, devices.devicesOf(DeviceType.C).size());
        assertEquals(8, devices.totalBusy() + 0 + freeTotal());
        assertEquals(0, devices.totalBusy());
    }

    private int freeTotal() {
        return devices.freeCount(DeviceType.A) + devices.freeCount(DeviceType.B)
                + devices.freeCount(DeviceType.C);
    }

    @Test
    @DisplayName("同类型设备可分配给不同进程，用完后不再分配")
    void exclusiveAllocation() {
        assertTrue(devices.request(DeviceType.A, 1, 3));
        assertTrue(devices.request(DeviceType.A, 2, 3));
        assertFalse(devices.request(DeviceType.A, 3, 3), "A 类只有 2 台，第 3 次申请必须失败");

        assertEquals(2, devices.busyCount(DeviceType.A));
        assertEquals(0, devices.freeCount(DeviceType.A));
        assertNotNull(devices.deviceOf(1));
        assertNotNull(devices.deviceOf(2));
        assertEquals(DeviceType.A, devices.deviceOf(1).getType());
        assertNull(devices.deviceOf(3));
        assertEquals(3, devices.deviceOf(1).getRemaining());
    }

    @Test
    @DisplayName("同一台设备不能被重复分配")
    void oneDeviceCannotBeAssignedTwice() {
        devices.request(DeviceType.B, 1, 2);
        Device device = devices.deviceOf(1);
        assertThrows(IllegalStateException.class, () -> device.assign(2, 2));
    }

    @Test
    @DisplayName("倒计时归零时报告给上层，释放后回到空闲")
    void countdownAndRelease() {
        devices.request(DeviceType.C, 5, 2);
        Device device = devices.deviceOf(5);

        List<Device> first = devices.tickDevices();
        assertTrue(first.isEmpty(), "剩余 1 时不应报告完成");
        assertEquals(1, device.getRemaining());

        List<Device> second = devices.tickDevices();
        assertEquals(1, second.size());
        assertEquals(device.getId(), second.get(0).getId());
        assertEquals(0, device.getRemaining());
        assertTrue(device.isBusy(), "倒计时结束的设备要等上层释放");

        devices.release(device);
        assertFalse(device.isBusy());
        assertEquals(3, devices.freeCount(DeviceType.C));
        assertThrows(IllegalStateException.class, device::release, "重复释放应被拒绝");
    }

    @Test
    @DisplayName("等待队列先进先出，设备释放后交给队首")
    void waitQueueIsFifo() {
        devices.request(DeviceType.A, 1, 1);
        devices.request(DeviceType.A, 2, 1);
        devices.enqueue(DeviceType.A, 3, 4);
        devices.enqueue(DeviceType.A, 4, 5);

        assertEquals(2, devices.waitQueueOf(DeviceType.A).size());
        assertEquals(3, devices.peekWait(DeviceType.A).pid());

        Device freed = devices.deviceOf(1);
        devices.release(freed);
        DeviceRequest granted = devices.dispatchToNext(freed);
        assertNotNull(granted);
        assertEquals(3, granted.pid());
        assertEquals(4, granted.duration());
        assertEquals(3, freed.getOwnerPid(), "队首进程应拿到设备");
        assertEquals(1, devices.waitQueueOf(DeviceType.A).size());
        assertEquals(4, devices.peekWait(DeviceType.A).pid());
    }

    @Test
    @DisplayName("队列为空时 dispatchToNext 返回 null")
    void dispatchOnEmptyQueue() {
        devices.request(DeviceType.A, 1, 1);
        Device device = devices.deviceOf(1);
        devices.release(device);
        assertNull(devices.dispatchToNext(device));
    }

    @Test
    @DisplayName("设备仍被占用时不能直接分配")
    void dispatchOnBusyDeviceRejected() {
        devices.request(DeviceType.A, 1, 1);
        devices.enqueue(DeviceType.A, 2, 1);
        Device device = devices.deviceOf(1);
        assertThrows(IllegalStateException.class, () -> devices.dispatchToNext(device));
    }

    @Test
    @DisplayName("撤销进程时释放它占用的全部设备并清理等待队列")
    void releaseAllAndRemoveWaiter() {
        devices.request(DeviceType.A, 1, 1);
        devices.request(DeviceType.A, 1, 1);
        devices.enqueue(DeviceType.B, 1, 2);
        assertEquals(2, devices.busyCount(DeviceType.A));

        assertTrue(devices.removeWaiter(1));
        assertEquals(0, devices.waitQueueOf(DeviceType.B).size());

        List<Device> released = devices.releaseAll(1);
        assertEquals(2, released.size());
        assertEquals(0, devices.totalBusy());
        assertNull(devices.deviceOf(1));
    }

    @Test
    @DisplayName("reset 之后所有设备空闲")
    void resetReleasesEverything() {
        devices.request(DeviceType.A, 1, 1);
        devices.request(DeviceType.B, 2, 1);
        devices.enqueue(DeviceType.C, 3, 1);
        devices.reset();
        assertEquals(0, devices.totalBusy());
        assertEquals(8, freeTotal());
        assertTrue(devices.waitQueueOf(DeviceType.C).isEmpty());
    }

    @Test
    @DisplayName("设备编号唯一且形如 A1、B2")
    void deviceIdsAreUnique() {
        List<Device> all = devices.devices();
        long distinct = all.stream().map(Device::getId).distinct().count();
        assertEquals(all.size(), distinct);
        assertTrue(all.get(0).getId().matches("[ABC][1-9]"));
    }

    @Test
    @DisplayName("时长 0 的设备在下一个时钟单位立即完成")
    void zeroDurationFinishesImmediately() {
        devices.request(DeviceType.B, 9, 0);
        List<Device> finished = devices.tickDevices();
        assertEquals(1, finished.size());
        assertEquals(9, finished.get(0).getOwnerPid());
    }
}
