import java.util.*;
import java.util.regex.*;

/** 复刻 NovelTask 核心算法，验证正确性。单文件，java Verify.java 直接跑。 */
public class Verify {
    static final Pattern IMG = Pattern.compile(
        "<img\\s+[^>]*?src\\s*=\\s*(\"[^\"]*\"|'[^']*'|\\S+)(\\s+[^>]*)?>",
        Pattern.CASE_INSENSITIVE);
    static final String PH = "\uE000IMG_%05d\uE001";

    static int tot, fill, un, rem;
    static String process(String text, List<String> md5s) {
        List<int[]> pos = new ArrayList<>();
        Matcher m = IMG.matcher(text);
        while (m.find()) pos.add(new int[]{m.start(), m.end()});
        tot = pos.size();
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (int i = 0; i < pos.size(); i++) {
            sb.append(text, last, pos.get(i)[0]);
            sb.append(String.format(PH, i + 1));
            last = pos.get(i)[1];
        }
        sb.append(text, last, text.length());
        String result = sb.toString();
        fill = 0;
        for (int i = 0; i < md5s.size(); i++) {
            if (i >= pos.size()) break;
            String ph = String.format(PH, i + 1);
            String tag = "<img src=\"" + md5s.get(i) + "\" align=\"center\">";
            result = result.replace(ph, tag);
            fill++;
        }
        un = Math.max(0, tot - fill);
        rem = Math.max(0, md5s.size() - fill);
        return result;
    }

    static void check(boolean c, String msg) {
        if (!c) throw new AssertionError("FAIL: " + msg);
        System.out.println("OK: " + msg);
    }

    public static void main(String[] a) {
        String novel = "第一章\n"
            + "<img src=\"http://p3-reading-sign.fqnovelpic.com/novel-pic-r/dccf08~tplv-noop.jpeg?lk3s=8d963091&x-expires=1886827569&x-signature=dN95\">\n"
            + "主角走进房间。\n"
            + "<img src=\"D636164A4D3DD63D76DE0EBA0004A6CE\" align=\"center\">\n"
            + "第二段正文。\n"
            + "<img src=\"http://img.example.com/other.png\">\n"
            + "第三段正文。\n";

        // 场景1：3 img == 3 md5
        String out = process(novel, List.of("AAA111","BBB222","CCC333"));
        check(tot==3&&fill==3&&un==0&&rem==0, "场景1：3img->3md5，全替换");
        check(out.contains("<img src=\"AAA111\" align=\"center\">")
            && out.contains("<img src=\"BBB222\" align=\"center\">")
            && out.contains("<img src=\"CCC333\" align=\"center\">"), "场景1：旧链接被替换为新 md5");
        check(!out.contains("http://p3-reading-sign"), "场景1：原长链接已消失");

        // 场景2：md5 多于 img
        out = process(novel, List.of("M1","M2","M3","M4","M5"));
        check(tot==3&&fill==3&&un==0&&rem==2, "场景2：md5 多于 img，多余 2 个未用");

        // 场景3：md5 少于 img
        out = process(novel, List.of("M1"));
        check(tot==3&&fill==1&&un==2&&rem==0, "场景3：md5 不足，未替换 2 个");

        // 场景4：相同/不同 img 混排，严格按出现顺序
        String n2 = "a<img src=\"X\">b<img src=\"X\">c<img src=\"Y\">d<img src=\"Y\">e";
        out = process(n2, List.of("P1","P2","P3","P4"));
        String expect = "a<img src=\"P1\" align=\"center\">b<img src=\"P2\" align=\"center\">"
            + "c<img src=\"P3\" align=\"center\">d<img src=\"P4\" align=\"center\">e";
        check(out.equals(expect), "场景4：混排 img 严格按出现顺序 P1..P4 对应");

        // 场景5：无 img
        out = process("纯文本无图", List.of("M1"));
        check(tot==0&&fill==0, "场景5：无 img 不改动");

        System.out.println("\n全部 5 场景通过。");
    }
}
