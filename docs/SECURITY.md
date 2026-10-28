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
