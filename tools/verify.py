# 复刻 NovelTask 的核心算法（占位 + 按出现顺序填充 md5），验证正确性。
import re

IMG_PATTERN = re.compile(
    r'<img\s+[^>]*?src\s*=\s*("[^"]*"|\'[^\']*\'|\S+)(\s+[^>]*)?>', re.IGNORECASE
)
PH = '\uE000IMG_%05d\uE001'

def process(text, md5s):
    positions = []
    for m in IMG_PATTERN.finditer(text):
        positions.append((m.start(), m.end()))
    sb = []
    last = 0
    for i, (s, e) in enumerate(positions):
        sb.append(text[last:s])
        sb.append(PH % (i + 1))
        last = e
    sb.append(text[last:])
    result = ''.join(sb)
    filled = 0
    for i, md5 in enumerate(md5s):
        if i >= len(positions):
            break
        ph = PH % (i + 1)
        tag = '<img src="%s" align="center">' % md5
        result = result.replace(ph, tag)
        filled += 1
    return result, len(positions), filled, max(0, len(positions) - filled), max(0, len(md5s) - filled)

novel = """第一章
<img src="http://p3-reading-sign.fqnovelpic.com/novel-pic-r/dccf0826dca94d59fc88209cc2435483~tplv-noop.jpeg?lk3s=8d963091&x-expires=1886827569&x-signature=dN95">
主角走进房间。
<img src="D636164A4D3DD63D76DE0EBA0004A6CE" align="center">
第二段正文。
<img src="http://img.example.com/other.png">
第三段正文。
"""

# 场景1：md5 数量 == img 数量（3==3）
out, tot, fill, un, rem = process(novel, ["AAA111", "BBB222", "CCC333"])
assert tot == 3 and fill == 3 and un == 0 and rem == 0, (tot, fill, un, rem)
assert '<img src="AAA111" align="center">' in out
assert '<img src="BBB222" align="center">' in out
assert '<img src="CCC333" align="center">' in out
assert 'http://p3-reading-sign' not in out   # 旧链接被替换
print("场景1 OK：3 个 img -> 3 个 md5，旧链接已替换")

# 场景2：md5 比 img 多（5 个 md5, 3 个 img）
out, tot, fill, un, rem = process(novel, ["M1","M2","M3","M4","M5"])
assert tot == 3 and fill == 3 and un == 0 and rem == 2
print("场景2 OK：md5 多于 img，多余 2 个不填，输出 2 个未用完")

# 场景3：md5 比 img 少（1 个 md5, 3 个 img）
out, tot, fill, un, rem = process(novel, ["M1"])
assert tot == 3 and fill == 1 and un == 2 and rem == 0
assert '<img src="M1" align="center">' in out
print("场景3 OK：md5 不足，未替换 2 个占位")

# 场景4：原文多个一模一样/不同的 img，按出现顺序一一对应
n2 = """a<img src="X">b<img src="X">c<img src="Y">d<img src="Y">e"""
out, tot, fill, un, rem = process(n2, ["P1","P2","P3","P4"])
assert tot == 4 and fill == 4
assert out == "a<img src=\"P1\" align=\"center\">b<img src=\"P2\" align=\"center\">c<img src=\"P3\" align=\"center\">d<img src=\"P4\" align=\"center\">e"
print("场景4 OK：相同/不同 img 混排，严格按出现顺序 P1..P4 对应")

# 场景5：无 img
out, tot, fill, un, rem = process("纯文本无图", ["M1"])
assert tot == 0 and fill == 0
print("场景5 OK：无 img 不改动")

print("\n全部 5 个场景通过。算法 = 占位符按出现顺序编号 -> 顺序填 md5 -> 多余忽略 / 不足留占位。")
