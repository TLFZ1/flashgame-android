# 敏感信息防护

## 调研结论（2026-10-28）

| 主题 | 结论 | 来源 |
|---|---|---|
| 泄漏了密钥先做什么 | **先吊销/轮换**，清历史可能根本没必要 | GitHub Docs |
| 重写历史（filter-repo/BFG） | 有代价：哈希全变、协作者必须重新 clone、**易"再污染"**、PR diff 会坏 | GitHub Docs |
| 主流防线 | **提交前拦截**：gitleaks / detect-secrets 的 pre-commit hook | 两者 README |
| 平台侧 | GitHub 自带 secret scanning、public repo 免费；可开 push protection | GitHub Docs |
| `git push -f` 能清敏感提交吗 | **不能**，只是移动引用；对象仍可按 SHA 访问 | 本项目实测 2026-10-28 |
| 真正清除 | 删库重建（新对象库）或联系 GitHub 支持请求 GC | 本项目实测 |

## 本项目三道防线

### 1. 提交前：本地自检脚本

```bash
tools/check-secrets.sh           # 工作区已跟踪文件
tools/check-secrets.sh staged    # 暂存区（钩子用）
tools/check-secrets.sh history   # 全部历史提交（推公开仓库前必跑）
```

规则在 `tools/secret-patterns.txt`（14 条：本机路径、内网 IP、常见令牌、私钥块）。
误报写进 `tools/secret-allowlist.txt`（路径正则白名单）。

### 2. 提交时：pre-commit 钩子

```bash
tools/install-hooks.sh          # 安装（只改本仓库 .git/hooks）
tools/install-hooks.sh uninstall
```

钩子跑 `staged` 模式，命中即拒绝提交。临时跳过：`git commit --no-verify`。

### 3. 平台侧：GitHub secret scanning + push protection

公开仓库免费。在 Settings → Code security 里开启；推送含令牌的内容会被 GitHub 直接拦下。

## 发版到公开仓库的流程

```bash
cd public-snapshot
tools/check-secrets.sh            # 1. 工作区干净
tools/check-secrets.sh history    # 2. 历史干净（关键！第一次 push 前就要过）
git add -A && git commit -m "..."  # 3. 提交
git push                          # 4. 推送
```

**第一次 push 之前必须过第 2 步**——推上去之后就没有"清干净"这个选项了，只有"删库重建"。

## 已经泄漏了怎么办

1. **是密钥/令牌？** 立刻吊销并换新的。清历史可以之后再说。
2. **是本机路径 / 内网 IP / 注释？** 评估：这些不构成访问凭据，风险等级低。
3. **要彻底清除**：
   - 选项 A：**删库重建**（新对象库，旧 SHA 全部 404）。代价：Release、star、fork、链接都要重来。
   - 选项 B：联系 GitHub 支持请求 GC 不可达对象（几天，不保证）。
   - `git push -f` **不算**——旧提交仍可通过 `/commit/<SHA>` 打开。
4. **重建前**：先把当前工作区/历史用 `check-secrets.sh` 过一遍，别带着同样的内容再推一次。

## 删库重建：标准流程（2026-10-28 实战两次）

> 适用：仓库刚建、没有 star/fork、没有协作者。否则先评估代价。

### 第 0 步：先让历史变干净（最容易漏）

删库重建**不会自动变干净** —— 如果还推同一份历史，泄漏会原样回来。

```bash
cd <public-repo>
tools/check-secrets.sh history     # 必须先过，否则等于白重建
```

历史里有泄漏时，二选一：

- **重建成单条提交**（推荐，最稳）：
  ```bash
  git checkout --orphan release-main
  git add -A
  git commit -m "Initial public release: <版本>"
  git branch -D main
  git branch -m release-main main
  git tag -f v<版本> HEAD
  ```
  好处：新仓库只有一条提交，内容 = 当前干净树，**零历史包袱**。
- 只改某一个文件的历史：`git filter-repo`（需另装）。**不要**用 `git filter-branch` 的 tree-filter 直接跑相对路径脚本，在临时目录里容易静默失败（本项目实测失败过一次）。

清理后确认只剩该留的引用：

```bash
git for-each-ref --format='%(refname)'   # 只应有 heads/main 和 tags/v*
git rev-list --all | wc -l               # 应为 1
```

### 第 1 步：删除远端仓库

1. 仓库 → Settings → 拉到底 **Danger Zone** → Delete this repository
2. 两步确认 + 输入 `<user>/<repo>`
3. **会要求 sudo 重新验证**（邮件 8 位验证码，页面 "Verify via email"）
   - 验证通过后，**之前挂起的删除会自动完成**，不需要再点一次
4. 确认：`curl -o /dev/null -w '%{http_code}' https://github.com/<user>/<repo>` → 404

### 第 2 步：同名重建

1. `https://github.com/new` → 填同名 → Public → 不勾 README/gitignore/license
2. 推送：
   ```bash
   git remote set-url origin git@github.com:<user>/<repo>.git
   git push -u origin main
   git push origin v<版本>
   ```

### 第 3 步：重发 Release

1. `https://github.com/<user>/<repo>/releases/new?tag=v<版本>&title=<标题>`（用 query 参数预填 tag/标题，最省事）
2. 填正文 → **Attach binaries** 挂安装包
   - 若系统文件对话框被其他窗口挡住，`type_text` 注入会失败；关掉挡住的窗口重试，或手动拖拽
3. Publish

### 第 4 步：验证清单

```bash
# 旧提交（两代之前的所有 SHA）必须 404
for sha in <old-sha...>; do curl -o /dev/null -w "%{http_code} " \
  https://github.com/<user>/<repo>/commit/$sha; done

# 当前内容干净
curl -sL https://raw.githubusercontent.com/<user>/<repo>/main/<敏感文件> | grep -nE "<模式>"

# 附件可下载且 md5 与原包一致
curl -sL -o /tmp/x.apk https://github.com/<user>/<repo>/releases/download/v<版本>/<包名>
md5sum /tmp/x.apk
```

### 本项目已发生的两次

| 时间 | 原因 | 结果 |
|---|---|---|
| 2026-10-28 13:4x | 首推快照含 `<盘符>:\\<目录>\\...` 与内网 IP | 删库重建，保留 12 条提交 |
| 2026-10-28 14:1x | 上一版历史里仍有 `<盘符>:\\<目录>\\...`（我加 adb 备用路径时带回去的） | 删库重建，**压成单条提交** `49e7eca` |