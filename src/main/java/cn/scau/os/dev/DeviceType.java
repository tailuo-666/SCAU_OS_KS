package cn.scau.os.dev;

/**
 * 设备类型：A、B、C 三类独占型设备。
 *
 * <p>同一类的不同实例被认为是"相同"的设备，进程申请时只说明要哪一类，
 * 由设备管理程序挑一个空闲实例给它。
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：三类设备的定义：A 类 2 台、B 类 3 台、C 类 3 台。</li>
 *   <li><b>从哪读起</b>：枚举常量后面直接跟着台数，一共就十来行。</li>
 *   <li><b>分工</b>：成员 A 主讲。</li>
 * </ul>
 */
public enum DeviceType {

    /** A 类设备 2 个。 */
    A("A", 2),
    /** B 类设备 3 个。 */
    B("B", 3),
    /** C 类设备 3 个。 */
    C("C", 3);

    private final String label;
    private final int count;

    DeviceType(String label, int count) {
        this.label = label;
        this.count = count;
    }

    public String getLabel() {
        return label;
    }

    /** 该类设备的实例个数。 */
    public int getCount() {
        return count;
    }

    /** 由指令里的字母取设备类型。 */
    public static DeviceType fromChar(char c) {
        switch (Character.toUpperCase(c)) {
            case 'A':
                return A;
            case 'B':
                return B;
            case 'C':
                return C;
            default:
                throw new IllegalArgumentException("未知设备类型：" + c);
        }
    }
}
