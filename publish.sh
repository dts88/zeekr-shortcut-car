#!/usr/bin/env bash
# 把本仓库发布到 GitHub（公开）。
#
# 前置条件：先自行登录一次 GitHub CLI（本步骤必须你亲自完成）：
#     gh auth login
#
# 然后运行：
#     bash publish.sh
#
# 之后每次改动只要 git push，GitHub Actions 会自动构建 APK。
set -euo pipefail

REPO_NAME="${REPO_NAME:-zeekr-shortcut-car}"
DESCRIPTION="Zeekr Shortcut (Car Version) - 极氪车机环视记录仪。代码基座为 EVCam (GPL-3.0)，极氪合成流格式参考 openavm-recorder 的公开技术资料。"

cd "$(dirname "$0")"

if ! gh auth status >/dev/null 2>&1; then
  echo "尚未登录 GitHub CLI。请先运行：gh auth login" >&2
  exit 1
fi

OWNER="$(gh api user --jq .login)"
echo "GitHub 账号: ${OWNER}"
echo "仓库名称  : ${REPO_NAME}（公开）"
echo

if gh repo view "${OWNER}/${REPO_NAME}" >/dev/null 2>&1; then
  echo "仓库已存在，直接推送。"
  git remote get-url origin >/dev/null 2>&1 \
    || git remote add origin "https://github.com/${OWNER}/${REPO_NAME}.git"
  git push -u origin main
else
  echo "创建公开仓库并推送..."
  gh repo create "${REPO_NAME}" \
    --public \
    --source=. \
    --remote=origin \
    --description "${DESCRIPTION}" \
    --push
fi

echo
echo "完成。"
echo "  仓库   : https://github.com/${OWNER}/${REPO_NAME}"
echo "  构建   : https://github.com/${OWNER}/${REPO_NAME}/actions"
echo
echo "打 tag 可自动发布 Release（APK 会挂在 Release 里）："
echo "  git tag v0.1.0-alpha && git push origin v0.1.0-alpha"
