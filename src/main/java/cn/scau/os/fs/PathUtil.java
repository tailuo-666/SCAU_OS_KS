package cn.scau.os.fs;

import java.util.ArrayList;
import java.util.List;

/**
 * 路径工具：把用户输入的路径统一成 {@code \DIR\FILE.EXT} 这种规范形式。
 *
 * <p>规则：
 * <ul>
 *   <li>分隔符 {@code \} 与 {@code /} 等价；</li>
 *   <li>路径一律转为大写（与目录项中名称的存储形式一致）；</li>
 *   <li>连续分隔符折叠成一个，末尾分隔符去掉（根目录除外）；</li>
 *   <li>绝对路径以 {@code \} 开头；相对路径按当前目录解析（{@code chdir} 之后生效）。</li>
 * </ul>
 * <h2>新手阅读导航</h2>
 * <ul>
 *   <li><b>是什么</b>：路径"翻译官"。用户在命令行敲 {@code ..\DOC\N01.T}，
 *       这里把它变成规范的 {@code \DOC\N01.T}。</li>
 *   <li><b>从哪读起</b>：{@link #normalize(String)} → {@link #resolve(String, String)}
 *       → {@link #splitName(String)}。前者处理 {@code .} 和 {@code ..}，
 *       中者拼接相对路径，后者把名字拆成"名 + 扩展名"。</li>
 *   <li><b>易错点</b>：根目录是 {@code \}，分割后要丢掉空串；
 *       {@code ..} 在根目录下仍然是根目录，不能越界。</li>
 *   <li><b>分工</b>：成员 B 主讲；成员 C 会问"命令行里的 cd .. 是怎么实现的"。</li>
 * </ul>
 */
public final class PathUtil {

    /** 根目录。 */
    public static final String ROOT = "\\";

    private PathUtil() {
    }

    /** 规范化路径，不解析相对路径。 */
    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return ROOT;
        }
        String s = raw.trim().replace('/', '\\').toUpperCase();
        StringBuilder sb = new StringBuilder();
        boolean lastWasSeparator = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (!lastWasSeparator) {
                    sb.append('\\');
                    lastWasSeparator = true;
                }
            } else {
                sb.append(c);
                lastWasSeparator = false;
            }
        }
        String result = sb.toString();
        if (!result.startsWith("\\")) {
            result = "\\" + result;
        }
        if (result.length() > 1 && result.endsWith("\\")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 把可能是相对路径的输入解析成绝对路径。
     *
     * @param currentDir 当前目录（绝对路径）
     * @param raw        用户输入
     */
    public static String resolve(String currentDir, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return normalize(currentDir);
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("\\") || trimmed.startsWith("/")) {
            return normalize(trimmed);
        }
        String dir = normalize(currentDir);
        return normalize(dir.equals(ROOT) ? "\\" + trimmed : dir + "\\" + trimmed);
    }

    /** 拆分路径为若干段（不含根）。 */
    public static List<String> segments(String path) {
        String normalized = normalize(path);
        List<String> list = new ArrayList<>();
        if (ROOT.equals(normalized)) {
            return list;
        }
        for (String seg : normalized.split("\\\\")) {
            if (!seg.isEmpty()) {
                list.add(seg);
            }
        }
        return list;
    }

    /** 父目录路径；根目录的父目录仍是根目录。 */
    public static String parent(String path) {
        String normalized = normalize(path);
        if (ROOT.equals(normalized)) {
            return ROOT;
        }
        int idx = normalized.lastIndexOf('\\');
        return idx <= 0 ? ROOT : normalized.substring(0, idx);
    }

    /** 路径末段的文件名（含扩展名）。 */
    public static String fileName(String path) {
        String normalized = normalize(path);
        int idx = normalized.lastIndexOf('\\');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    /** 是否根目录。 */
    public static boolean isRoot(String path) {
        return ROOT.equals(normalize(path));
    }

    /** 拼接目录与名称。 */
    public static String join(String dir, String name) {
        String d = normalize(dir);
        return normalize(ROOT.equals(d) ? "\\" + name : d + "\\" + name);
    }

    /**
     * 把 "P01.E" 拆成 名称 "P01" + 扩展名 'e'。
     * 无扩展名时返回 {name, 0}。
     */
    public static NameParts splitName(String fileName) {
        String upper = fileName.toUpperCase();
        int dot = upper.lastIndexOf('.');
        if (dot < 0) {
            return new NameParts(DirEntry.normalizeName(upper), (char) 0);
        }
        String name = upper.substring(0, dot);
        String ext = upper.substring(dot + 1);
        if (ext.isEmpty() || ext.length() > 1) {
            throw new FsException("扩展名必须是 1 个字符：" + fileName);
        }
        return new NameParts(DirEntry.normalizeName(name), DirEntry.normalizeExtension(ext.charAt(0)));
    }

    /** 名称拆分结果。 */
    public static final class NameParts {
        public final String name;
        public final char extension;

        NameParts(String name, char extension) {
            this.name = name;
            this.extension = extension;
        }
    }
}
