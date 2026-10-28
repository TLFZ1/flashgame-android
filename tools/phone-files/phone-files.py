#!/usr/bin/env python3
"""手机文件管理器（网页版）

在电脑上运行，浏览器打开 http://127.0.0.1:8100 即可浏览/下载/上传/删除手机文件。
底层走 adb（推荐无线），不需要在手机上装任何 App。

用法: python phone-files.py [端口]
"""
import os
import re
import shlex
import shutil
import subprocess
import sys
import threading
import time
import tempfile
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))


def _find_adb():
    """按优先级找 adb，避免写死路径（换机器/改目录名都能用）。"""
    cands = [
        os.environ.get('ADB_PATH'),
        os.path.join(HERE, 'platform-tools', 'adb.exe'),     # 便携包：adb 放在同目录
        os.path.join(HERE, 'platform-tools', 'adb'),
        os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
        shutil.which('adb'),
    ]
    for c in cands:
        if c and os.path.exists(c):
            return c
    return 'adb'          # 最后退回 PATH




def _discover():
    """用 adb mdns / devices 自动发现手机地址，返回候选列表。"""
    cands = []
    try:
        d = subprocess.run([ADB, 'devices'], capture_output=True, timeout=10).stdout.decode('utf-8', 'replace')
        for line in d.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and ':' in parts[0] and parts[1] == 'device':
                cands.append((0, parts[0]))
    except Exception:
        pass
    try:
        out = subprocess.run([ADB, 'mdns', 'services'], capture_output=True, timeout=20).stdout.decode('utf-8', 'replace')
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 3 and ':' in parts[-1]:
                kind = parts[-2]
                prio = 1 if '_adb._tcp' in kind else 2
                cands.append((prio, parts[-1].strip()))
    except Exception:
        pass
    seen, ordered = set(), []
    for _, a in sorted(cands, key=lambda x: x[0]):
        if a not in seen:
            seen.add(a)
            ordered.append(a)
    return ordered


def _try_connect(addr):
    """连接并确认可用；首次连接可能较慢（等 adb server / mdns），重试一次。"""
    for _ in range(2):
        try:
            subprocess.run([ADB, 'connect', addr], capture_output=True, timeout=8)
            st = subprocess.run([ADB, '-s', addr, 'get-state'], capture_output=True, timeout=8).stdout.decode('utf-8', 'replace').strip()
            if st == 'device':
                return True
        except Exception:
            pass
        time.sleep(1)
    return False




def _model_of(addr):
    """拿手机型号用于显示（连上才有效）。"""
    try:
        out = subprocess.run([ADB, '-s', addr, 'shell', 'getprop', 'ro.product.model'], capture_output=True, timeout=8)
        return out.stdout.decode('utf-8', 'replace').strip()
    except Exception:
        return ''


def _choose(cands):
    """cands: [(addr, model)]。多台时让用户选，返回地址。"""
    if not cands:
        return None
    if len(cands) == 1:
        return cands[0][0]
    print('')
    print('发现 %d 台手机，请选择要连接的一台：' % len(cands))
    for i, (addr, model) in enumerate(cands, 1):
        suffix = ('  [' + model + ']') if model else ''
        print('  %d) %s%s' % (i, addr, suffix))
    if not sys.stdin.isatty():
        print('（非交互环境，自动选择第 1 台：%s）' % cands[0][0])
        return cands[0][0]
    while True:
        try:
            ans = input('输入编号 1-%d（回车默认 1）: ' % len(cands)).strip()
        except (EOFError, KeyboardInterrupt):
            return cands[0][0]
        if ans == '':
            return cands[0][0]
        if ans.isdigit() and 1 <= int(ans) <= len(cands):
            return cands[int(ans) - 1][0]
        print('  无效编号，请重新输入')

def _read_cfg():
    """只读配置，不做任何探测（启动要快）。"""
    cfg = os.path.join(HERE, 'phone.txt')
    force_pick = os.environ.get('PHONE_PICK') == '1'
    if os.path.exists(cfg) and not force_pick:
        for line in open(cfg, encoding='utf-8', errors='replace'):
            line = line.strip()
            if line and not line.startswith('#'):
                if re.match(r'^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]{1,5}$', line):
                    return line, cfg
                print('phone.txt 里的地址格式不对，忽略: ' + line)
    env = '' if force_pick else os.environ.get('PHONE_DEV', '')
    if env:
        return env, cfg
    return '', cfg


def _find_dev():
    """启动时只读配置；探测/发现放到后台线程，避免窗口迟迟不出来。"""
    return _read_cfg()[0]


def _resolve_dev(timeout_print=True):
    """后台解析真正可用的手机地址；成功则写入 phone.txt 并更新全局 DEV。"""
    global DEV
    cfg = os.path.join(HERE, 'phone.txt')
    if DEV:
        print('正在连接 ' + DEV + ' …', flush=True)
        if _try_connect(DEV):
            print('已连接: ' + DEV, flush=True)
            return DEV
        print('保存的地址连不上，重新搜索…', flush=True)
    print('正在搜索同一 WiFi 下的手机 …', flush=True)
    working = []
    for cand in _discover():
        if any(cand == w[0] for w in working):
            continue
        if _try_connect(cand):
            working.append((cand, _model_of(cand)))
    seen_ip, uniq = set(), []
    for addr, model in working:
        ip = addr.rsplit(':', 1)[0]
        if ip in seen_ip:
            continue
        seen_ip.add(ip)
        uniq.append((addr, model))
    chosen = _choose(uniq)
    if chosen:
        try:
            open(cfg, 'w', encoding='utf-8').write(chosen + chr(10))
        except Exception:
            pass
        DEV = chosen
        print('已连接: ' + chosen + ' （已写入 phone.txt）', flush=True)
        return chosen
    print('没找到手机：请确认手机已开「无线调试」、且和电脑在同一 WiFi。', flush=True)
    print('（没有 USB 线时可用 pair.bat 配对；有 USB 线时用 adb-tcpip.bat）', flush=True)
    return ''


ADB = _find_adb()
DEV = _find_dev()
ROOT = '/sdcard'
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8100

# 没有配置也能启动：服务先起来，后台线程去发现/询问手机地址


_lock = threading.Lock()
_conn_lock = threading.Lock()   # 只保护"重连"这一个动作，不阻塞请求
_last_state = {'state': 'unknown', 'checked': 0, 'reconnects': 0}


def _raw(args, binary=False):
    try:
        p = subprocess.run([ADB, '-s', DEV] + args, capture_output=True, timeout=25)
    except subprocess.TimeoutExpired:
        return b'' if binary else 'timeout'
    if binary:
        return p.stdout
    return p.stdout.decode('utf-8', 'replace') + p.stderr.decode('utf-8', 'replace')


def device_state():
    try:
        p = subprocess.run([ADB, '-s', DEV, 'get-state'], capture_output=True, timeout=8)
    except Exception:
        return 'missing'
    return (p.stdout.decode('utf-8', 'replace').strip() or 'missing')


def ensure_connection(force=False):
    """断线自动重连。绝不阻塞并发请求：正在重连时直接返回缓存状态。"""
    now = time.time()
    if not force and _last_state['state'] == 'device' and now - _last_state.get('checked', 0) < 2:
        return True
    if not _conn_lock.acquire(blocking=False):
        return _last_state.get('state') == 'device'
    try:
        st = device_state()
        if st == 'device' and not force:
            _last_state['state'] = 'device'
            _last_state['checked'] = int(time.time())
            return True
        for args in (['disconnect', DEV], ['connect', DEV]):
            try:
                subprocess.run([ADB] + args, capture_output=True, timeout=10)
            except Exception:
                pass
        st = device_state()
        if st != 'device':
            try:
                subprocess.run([ADB, 'reconnect'], capture_output=True, timeout=10)
            except Exception:
                pass
            st = device_state()
        _last_state['state'] = st if st == 'device' else 'offline'
        _last_state['checked'] = int(time.time())
        if _last_state['state'] == 'device':
            _last_state['reconnects'] += 1
            return True
        return False
    finally:
        _conn_lock.release()


def adb(args, binary=False):
    if ensure_connection():
        return _raw(args, binary)
    # 断线时再试一次
    ensure_connection(force=True)
    return _raw(args, binary)


def sh(cmd):
    return adb(['shell', cmd])


def list_dir(path):
    path = path.rstrip('/') or '/'
    # 一条命令批量 stat（每个文件单独 fork 会慢 10 倍以上）
    script = (
        'cd %s 2>/dev/null || exit 3; '
        "stat -c '%%F|%%s|%%n' * .* 2>/dev/null" % shlex.quote(path)
    )
    out = sh(script)
    items = []
    for line in out.splitlines():
        parts = line.split('|', 2)
        if len(parts) != 3:
            continue
        t, s, name = parts
        if name in ('.', '..'):
            continue
        try:
            size = int(s)
        except ValueError:
            size = 0
        items.append({'name': name, 'dir': 'directory' in t, 'size': size})
    items.sort(key=lambda x: (not x['dir'], x['name'].lower()))
    return items


PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>手机文件</title>
<style>
body{font-family:system-ui,"Microsoft YaHei",sans-serif;background:#15171c;color:#e6e8ee;margin:0;padding:16px}
a{color:#7fb8ff;text-decoration:none} a:hover{text-decoration:underline}
table{width:100%;border-collapse:collapse;margin-top:10px}
td,th{padding:8px 10px;border-bottom:1px solid #2a2e38;text-align:left;font-size:14px}
th{color:#8b93a7;font-weight:500;font-size:12px}
tr:hover{background:#1c1f26}
.bar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:10px 0}
button{background:#2b3140;color:#e6e8ee;border:1px solid #3a4152;border-radius:8px;padding:6px 12px;cursor:pointer}
button:hover{background:#364156}
input[type=file]{color:#c9cede}
.crumb{font-size:15px;color:#9aa3b8}
.small{color:#8b93a7;font-size:12px}
</style></head><body>
<div class="crumb">{{CRUMB}}</div>
<div class="bar">
  <form method="post" action="/upload" enctype="multipart/form-data">
    <input type="hidden" name="path" value="{{PATH}}">
    <input type="file" name="file" multiple>
    <button type="submit">上传文件</button>
  </form>
  <form method="post" action="/upload" enctype="multipart/form-data">
    <input type="hidden" name="path" value="{{PATH}}">
    <input type="file" name="file" webkitdirectory multiple>
    <button type="submit">上传整个文件夹</button>
  </form>
  <button onclick="mkdir()">新建文件夹</button>
  <button onclick="location.reload()">刷新</button>
  <button onclick="fetch('/reconnect',{method:'POST'}).then(()=>location.reload())">重连手机</button>
  <span id="st" class="small" style="margin-left:6px">连接中…</span>
  <span class="small">{{COUNT}} 项</span>
</div>
<table><tr><th>名称</th><th>大小</th><th>操作</th></tr>{{ROWS}}</table>
<script>
function del(p){if(confirm('删除 '+p+' ?')){fetch('/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:p})}).then(()=>location.reload())}}
function ren(p){var n=prompt('新名称',p.split('/').pop());if(n){fetch('/rename',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:p,newname:n})}).then(()=>location.reload())}}
function poll(){
  fetch('/status').then(r=>r.json()).then(d=>{
    var e=document.getElementById('st');
    if(d.state==='device'){e.textContent='● 已连接';e.style.color='#6ee787';}
    else{e.textContent='○ 未连接（自动重连中…）';e.style.color='#ff7b72';}
  }).catch(()=>{});
}
setInterval(poll,5000);poll();
function mkdir(){var n=prompt('文件夹名','');if(n){fetch('/mkdir',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:'{{PATH}}/'+n})}).then(()=>location.reload())}}
</script></body></html>"""


def human(n):
    for unit in ('B', 'KB', 'MB', 'GB'):
        if n < 1024 or unit == 'GB':
            return ('%.1f %s' % (n, unit)) if unit != 'B' else ('%d B' % n)
        n /= 1024.0


class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *a):
        pass

    def _send(self, code, body, ctype='text/html; charset=utf-8', extra=None):
        if isinstance(body, str):
            body = body.encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            pass   # 浏览器刷新/关闭页面时正常现象，不打印堆栈

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        if u.path == '/':
            path = q.get('path', [ROOT])[0]
            items = list_dir(path)
            rows = []
            if path.rstrip('/') != ROOT:
                parent = os.path.dirname(path.rstrip('/')) or '/'
                rows.append('<tr><td><a href="/?path=%s">⬆ 上一级</a></td><td></td><td></td></tr>'
                            % urllib.parse.quote(parent))
            for it in items:
                full = path.rstrip('/') + '/' + it['name']
                if it['dir']:
                    nm = '<a href="/?path=%s">📁 %s</a>' % (urllib.parse.quote(full), it['name'])
                    size = '—'
                else:
                    nm = '📄 ' + it['name']
                    size = human(it['size'])
                ops = '<a href="/download?path=%s">下载</a> · <a href="#" onclick="ren(\'%s\');return false">改名</a> · <a href="#" onclick="del(\'%s\');return false">删除</a>' % (
                    urllib.parse.quote(full), full.replace("'", "\\'"), full.replace("'", "\\'"))
                if it['dir']:
                    ops = '<a href="#" onclick="ren(\'%s\');return false">改名</a> · <a href="#" onclick="del(\'%s\');return false">删除</a>' % (
                        full.replace("'", "\\'"), full.replace("'", "\\'"))
                rows.append('<tr><td>%s</td><td class="small">%s</td><td class="small">%s</td></tr>' % (nm, size, ops))
            parts = [p for p in path.split('/') if p]
            crumb = '<a href="/">手机存储</a>'
            acc = ''
            for p in parts:
                acc += '/' + p
                crumb += ' / <a href="/?path=%s">%s</a>' % (urllib.parse.quote(acc), p)
            html = (PAGE.replace('{{CRUMB}}', crumb)
                        .replace('{{PATH}}', path)
                        .replace('{{COUNT}}', str(len(items)))
                        .replace('{{ROWS}}', ''.join(rows)))
            return self._send(200, html)
        if u.path == '/status':
            st = device_state()
            if st != 'device':
                ensure_connection()
                st = device_state()
            import json as _j
            return self._send(200, _j.dumps({'state': st, 'reconnects': _last_state['reconnects']}), 'application/json')
        if u.path == '/download':
            path = q.get('path', [''])[0]
            if not path:
                return self._send(400, 'missing path')
            data = adb(['exec-out', 'cat', path], binary=True)
            name = os.path.basename(path)
            quoted = urllib.parse.quote(name)
            return self._send(200, data, 'application/octet-stream',
                              {'Content-Disposition': "attachment; filename*=UTF-8''" + quoted})
        return self._send(404, 'not found')

    def _json(self):
        n = int(self.headers.get('Content-Length', 0))
        import json
        return json.loads(self.rfile.read(n).decode('utf-8'))

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == '/upload':
            ctype = self.headers.get('Content-Type', '')
            m = re.search(r'boundary=(.+)', ctype)
            if not m:
                return self._send(400, 'bad upload')
            boundary = m.group(1).strip().strip('"').encode()
            n = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(n)
            # 先取 path 字段（决定推到哪个目录），再处理文件段
            dest = ROOT
            for part in body.split(b'--' + boundary):
                if b'name="path"' in part and b'filename=' not in part:
                    _, _, d = part.partition(b'\r\n\r\n')
                    cand = d.rstrip(b'\r\n').decode('utf-8', 'replace').strip()
                    if cand.startswith('/'):
                        dest = cand
                    break
            saved = 0
            for part in body.split(b'--' + boundary):
                if b'filename="' not in part:
                    continue
                head, _, data = part.partition(b'\r\n\r\n')
                fm = re.search(rb'filename="([^"]*)"', head)
                if not fm:
                    continue
                fname = fm.group(1).decode('utf-8', 'replace')
                if not fname:
                    continue
                data = data.rstrip(b'\r\n')
                rel = fname.replace('\\', '/').lstrip('/')
                remote = dest.rstrip('/') + '/' + rel
                rdir = remote.rsplit('/', 1)[0]
                if rdir and rdir != dest.rstrip('/'):
                    sh('mkdir -p ' + shlex.quote(rdir))
                tmp = os.path.join(tempfile.gettempdir(), 'phonefiles_up.bin')
                with open(tmp, 'wb') as f:
                    f.write(data)
                adb(['push', tmp, remote])
                os.unlink(tmp)
                saved += 1
            return self._send(200, '<meta charset="utf-8">已上传 %d 个文件<br><a href="/">返回</a>' % saved)
        if u.path == '/status':
            st = device_state()
            if st != 'device':
                ensure_connection()
                st = device_state()
            import json as _j
            return self._send(200, _j.dumps({'state': st, 'reconnects': _last_state['reconnects']}), 'application/json')
        if u.path == '/reconnect':
            out = adb(['connect', DEV]) + adb(['devices'])
            return self._send(200, '{"ok":true,"out":%s}' % __import__('json').dumps(out), 'application/json')
        if u.path in ('/delete', '/mkdir', '/rename'):
            d = self._json()
            path = d.get('path', '')
            if u.path == '/delete':
                sh('rm -rf ' + shlex.quote(path))
            elif u.path == '/mkdir':
                sh('mkdir -p ' + shlex.quote(path))
            else:
                new = os.path.join(os.path.dirname(path.rstrip('/')), d.get('newname', ''))
                sh('mv ' + shlex.quote(path) + ' ' + shlex.quote(new))
            return self._send(200, '{"ok":true}', 'application/json')
        return self._send(404, 'not found')


def _already_running(port):
    """端口上若已跑着本工具，返回 True（避免开第二个实例、开第二个页面）。"""
    try:
        import urllib.request
        with urllib.request.urlopen('http://127.0.0.1:%d/status' % port, timeout=0.8) as r:
            body = r.read(200)
        return b'"state"' in body and b'"reconnects"' in body
    except Exception:
        return False


if __name__ == '__main__':
    # 先占端口：绑得上就继续；绑不上再看是不是本工具已经在跑
    ThreadingHTTPServer.allow_reuse_address = False
    srv = None
    use_port = PORT
    for cand in range(PORT, PORT + 10):
        try:
            srv = ThreadingHTTPServer(('127.0.0.1', cand), H)
            use_port = cand
            break
        except OSError:
            if _already_running(cand):
                url0 = 'http://127.0.0.1:%d' % cand
                print('已经有一个在运行: ' + url0)
                print('请直接用那个已打开的页面（本窗口即将关闭，不再开新页面）')
                time.sleep(1.0)
                sys.exit(0)
            continue
    if srv is None:
        print('端口 %d-%d 都被占用，请关掉旧的黑窗口再试' % (PORT, PORT + 9))
        sys.exit(2)
    if use_port != PORT:
        print('端口 %d 被占用，改用 %d' % (PORT, use_port))
    url = 'http://127.0.0.1:%d' % use_port
    print('手机文件管理器已启动: ' + url)
    print('（关掉这个窗口就停止服务）')
    if os.environ.get('NO_BROWSER') != '1':
        import threading
        import webbrowser
        threading.Timer(1.2, lambda: webbrowser.open(url)).start()
    # 手机连接放到后台，页面可以先打开、先显示"连接中"
    import threading as _th
    _th.Thread(target=_resolve_dev, daemon=True).start()
    def _watchdog():
        while True:
            try:
                ensure_connection()
            except Exception:
                pass
            time.sleep(20)
    threading.Thread(target=_watchdog, daemon=True).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass