package cn.scau.os.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPU 指令解释器单元测试：单字节编码、汇编、反汇编、非法指令。
 *
 * <p>指令集一共 5 类，每条指令 1 字节：
 * <pre>
 *   x=n    n ∈ 0~99           机器码 = n
 *   x++                        100
 *   x--                        101
 *   end                        102
 *   !Xd    X ∈ {A,B,C}, d ∈ 0~9  机器码 = 103 + 类别×10 + 时长
 * </pre>
 */
@DisplayName("CPU 指令解释器")
class InstructionCodecTest {

    @Test
    @DisplayName("赋值指令的机器码就是数值本身")
    void assignEncoding() {
        assertEquals(0, InstructionCodec.encodeAssign(0) & 0xFF);
        assertEquals(42, InstructionCodec.encodeAssign(42) & 0xFF);
        assertEquals(99, InstructionCodec.encodeAssign(99) & 0xFF);
        assertThrows(AssemblyException.class, () -> InstructionCodec.encodeAssign(100));
        assertThrows(AssemblyException.class, () -> InstructionCodec.encodeAssign(-1));
    }

    @Test
    @DisplayName("设备指令 = 103 + 类别×10 + 时长")
    void deviceEncoding() {
        assertEquals(103, InstructionCodec.encodeDevice('A', 0) & 0xFF);
        assertEquals(106, InstructionCodec.encodeDevice('A', 3) & 0xFF);
        assertEquals(113, InstructionCodec.encodeDevice('B', 0) & 0xFF);
        assertEquals(118, InstructionCodec.encodeDevice('B', 5) & 0xFF);
        assertEquals(123, InstructionCodec.encodeDevice('C', 0) & 0xFF);
        assertEquals(132, InstructionCodec.encodeDevice('C', 9) & 0xFF);
        assertThrows(AssemblyException.class, () -> InstructionCodec.encodeDevice('D', 1));
        assertThrows(AssemblyException.class, () -> InstructionCodec.encodeDevice('A', 10));
    }

    @Test
    @DisplayName("解码 5 类指令，种类与参数都正确")
    void decoding() {
        assertEquals(InstructionCodec.Decoded.Kind.ASSIGN, InstructionCodec.decode(7).getKind());
        assertEquals(7, InstructionCodec.decode(7).getValue());

        assertEquals(InstructionCodec.Decoded.Kind.INCREMENT, InstructionCodec.decode(100).getKind());
        assertEquals(InstructionCodec.Decoded.Kind.DECREMENT, InstructionCodec.decode(101).getKind());
        assertEquals(InstructionCodec.Decoded.Kind.END, InstructionCodec.decode(102).getKind());

        InstructionCodec.Decoded device = InstructionCodec.decode(127);
        assertEquals(InstructionCodec.Decoded.Kind.DEVICE, device.getKind());
        assertEquals('C', device.getDevice());
        assertEquals(4, device.getDuration());
        assertEquals("!C4", device.getText());
    }

    @Test
    @DisplayName("byte 有符号，103 以上必须先转无符号再解码")
    void signedByteMustBeMasked() {
        byte raw = (byte) 132;
        assertEquals(-124, (int) raw, "132 存进 byte 会变成负数");
        InstructionCodec.Decoded decoded = InstructionCodec.decode(raw);
        assertEquals(InstructionCodec.Decoded.Kind.DEVICE, decoded.getKind());
        assertEquals('C', decoded.getDevice());
        assertEquals(9, decoded.getDuration());
    }

    @Test
    @DisplayName("133~255 是非法指令")
    void invalidOpcodes() {
        assertTrue(InstructionCodec.decode(133).isInvalid());
        assertTrue(InstructionCodec.decode(200).isInvalid());
        assertTrue(InstructionCodec.decode(255).isInvalid());
        assertFalse(InstructionCodec.decode(132).isInvalid());
    }

    @Test
    @DisplayName("汇编：空白、注释与大小写都能正确处理")
    void assembleWithCommentsAndSpaces() {
        String source = "; 注释行\n"
                + "# 另一种注释\n"
                + "\n"
                + "x = 10\n"
                + "  X++  \n"
                + "x--\n"
                + "! b 5\n"
                + "END\n";
        byte[] code = InstructionCodec.assemble(source);
        assertArrayEquals(new byte[]{10, 100, 101, 118, 102}, code);
    }

    @Test
    @DisplayName("汇编：非法输入报出行号")
    void assembleReportsLineNumber() {
        AssemblyException e = assertThrows(AssemblyException.class,
                () -> InstructionCodec.assemble("x=10\nx=100\nend"));
        assertTrue(e.getMessage().contains("2"), "错误信息应指出第 2 行：" + e.getMessage());

        assertThrows(AssemblyException.class, () -> InstructionCodec.assemble("x=-1"));
        assertThrows(AssemblyException.class, () -> InstructionCodec.assemble("x+"));
        assertThrows(AssemblyException.class, () -> InstructionCodec.assemble("!D3"));
        assertThrows(AssemblyException.class, () -> InstructionCodec.assemble("!A"));
        assertThrows(AssemblyException.class, () -> InstructionCodec.assemble("!AX"));
        assertThrows(AssemblyException.class, () -> InstructionCodec.assemble("goto 1"));
    }

    @Test
    @DisplayName("空源码编译出空数组")
    void assembleEmptySource() {
        assertEquals(0, InstructionCodec.assemble(null).length);
        assertEquals(0, InstructionCodec.assemble("").length);
        assertEquals(0, InstructionCodec.assemble("\n\n; only comments\n").length);
    }

    @Test
    @DisplayName("反汇编是汇编的逆运算")
    void disassemblyRoundTrip() {
        byte[] code = {0, 12, 100, 101, 106, 118, (byte) 129, 102};
        List<String> lines = InstructionCodec.disassemble(code);
        assertEquals(List.of("x=0", "x=12", "x++", "x--", "!A3", "!B5", "!C6", "end"), lines);

        byte[] again = InstructionCodec.assemble(InstructionCodec.disassembleToText(code));
        assertArrayEquals(code, again);
    }

    @Test
    @DisplayName("默认程序都能被反汇编成合法指令")
    void defaultProgramsAreValid() {
        byte[] p03 = InstructionCodec.assemble("x=1\nx++\nx++\nx++\nx++\nx++\nx++\nend");
        assertEquals(8, p03.length, "P03 共 8 条指令，必然触发时间片切换");
        for (String line : InstructionCodec.disassemble(p03)) {
            assertFalse(line.startsWith("非法"), "不应出现非法指令：" + line);
        }
    }
}
