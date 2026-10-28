#!/usr/bin/env bash
# 安装/卸载 git pre-commit 钩子（本仓库专用，不改全局配置）
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
H="$ROOT/.git/hooks/pre-commit"
case "${1:-install}" in
  install)
    cat > "$H" <<'EOF'
#!/usr/bin/env bash
ROOT="$(git rev-parse --show-toplevel)"
exec "$ROOT/tools/check-secrets.sh" staged
EOF
    chmod +x "$H"
    echo "已安装 pre-commit 钩子：$H"
    echo "跳过检查：git commit --no-verify"
    ;;
  uninstall)
    rm -f "$H"
    echo "已卸载 pre-commit 钩子"
    ;;
  *) echo "用法: $0 [install|uninstall]"; exit 2;;
esac
