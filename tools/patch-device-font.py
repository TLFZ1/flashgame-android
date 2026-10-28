
import zlib, struct, sys

SRC = sys.argv[1] if len(sys.argv) > 1 else 'input.swf'
DST = sys.argv[2] if len(sys.argv) > 2 else 'output.swf'
OLD = '\uFF2D\uFF33 \uFF30\u30B4\u30B7\u30C3\u30AF'   # MS P Gothic
NEW = '\uFF2D\uFF33 \uFF30\u660E\u671D'              # MS P Mincho
old_b = OLD.encode('utf-8'); new_b = NEW.encode('utf-8')

raw = open(SRC, 'rb').read()
compressed = raw[:3] == b'CWS'
body = zlib.decompress(raw[8:]) if compressed else raw[8:]

nbits = body[0] >> 3
rect_len = (5 + 4 * nbits + 7) // 8
start = rect_len + 4
print('rect_len', rect_len, 'tags start', start)

pos = start
tags = []
while pos + 2 <= len(body):
    rec = struct.unpack_from('<H', body, pos)[0]
    code = rec >> 6; ln = rec & 0x3f; hdr = 2
    if ln == 0x3f:
        ln = struct.unpack_from('<I', body, pos + 2)[0]; hdr = 6
    tags.append((pos, hdr, code, ln))
    pos += hdr + ln
    if code == 0: break
print('tags', len(tags), 'font tags', sum(1 for t in tags if t[2] in (48, 75)))

target = None
for (p, hdr, code, ln) in tags:
    if code in (48, 75):
        seg = body[p + hdr:p + hdr + ln]
        i = seg.find(old_b)
        if i > 0:
            target = (p, hdr, code, ln, i)
            print('found in tag', p, 'code', code, 'offset', i, 'prev byte', hex(seg[i-1]))
            break

if not target:
    sys.exit('not found')

p, hdr, code, ln, i = target
prev = body[p + hdr + i - 1]
assert prev == len(old_b) + 1, ('unexpected length byte', prev)
seg = body[p + hdr:p + hdr + ln]
new_seg = seg[:i - 1] + bytes([len(new_b) + 1]) + new_b + b'\x00' + seg[i + len(old_b) + 1:]
delta = len(new_seg) - ln
print('delta', delta)

out = bytearray(body)
out[p + hdr:p + hdr + ln] = new_seg
# tag header length
if hdr == 2:
    val = (code << 6) | (len(new_seg) & 0x3f)
    if len(new_seg) >= 0x3f:
        sys.exit('tag grew past 63; needs 6-byte header rewrite')
    struct.pack_into('<H', out, p, val)
else:
    struct.pack_into('<I', out, p + 2, len(new_seg))

data = bytes(out)
head = bytearray(raw[:8])
head[0:3] = b'CWS' if compressed else b'FWS'
struct.pack_into('<I', head, 4, 8 + len(data))
open(DST, 'wb').write(bytes(head) + (zlib.compress(data, 9) if compressed else data))
print('wrote', DST)
