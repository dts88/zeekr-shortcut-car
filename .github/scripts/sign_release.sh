#!/usr/bin/env bash
# 用项目所有者的私有密钥给正式版重新签名：从仓库里公开的测试密钥轮换过去（APK Signature Scheme v3）。
# 发布任务（打 v* 标签时）和手动触发的签名自检都用它。
#
#   sign_release.sh <输入.apk> <输出.apk>
#
# 环境变量：
#   RELEASE_KEYSTORE           私钥文件（PKCS#12，release_key.py 生成，别名 zeekr-release）
#   RELEASE_KEYSTORE_PASSWORD  它的密码
#   RELEASE_CERT_SHA256        它的证书 SHA-256：签完核对，防止用错密钥
#
# - 轮换证明每次现做：旧密钥给新密钥签字。旧密钥带「已装数据」权限（车机从旧签名升级上来，设置和数据都在），
#   不带「回滚」权限（升级到新签名之后，只用旧密钥签的包装不上去）。
# - --rotation-min-sdk-version 28：车机是 Android 12（API 32），轮换默认只在 Android 13 起生效。
# - 签完核对：v3 签名者是新证书，轮换证明是「旧 → 新」两把、旧的权限如上；有一样不对就失败，不发布。
set -euo pipefail

in=$1
out=$2
: "${RELEASE_KEYSTORE:?}" "${RELEASE_KEYSTORE_PASSWORD:?}" "${RELEASE_CERT_SHA256:?}"
expected=$(printf '%s' "$RELEASE_CERT_SHA256" | tr -d ':' | tr 'A-F' 'a-f')
# keystore/release.jks（AOSP 测试密钥）的证书：2.13 及以前的版本都是它签的，车机上装着的就是它
test_key=a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc

apksigner="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner"
old=(--ks keystore/release.jks --ks-key-alias apkeasytool --ks-pass pass:android)
new=(--ks "$RELEASE_KEYSTORE" --ks-type PKCS12 --ks-key-alias zeekr-release --ks-pass env:RELEASE_KEYSTORE_PASSWORD)

lineage=$(mktemp)
trap 'rm -f "$lineage"' EXIT
"$apksigner" rotate --out "$lineage" --old-signer "${old[@]}" --new-signer "${new[@]}"
"$apksigner" sign "${old[@]}" --next-signer "${new[@]}" \
  --lineage "$lineage" --rotation-min-sdk-version 28 --out "$out" "$in"

verify=$("$apksigner" verify --verbose --print-certs "$out")
history=$("$apksigner" lineage --in "$out" --print-certs)
echo "$verify"
echo "$history"

fail() {
  echo "::error::$1"
  exit 1
}
grep -qx 'Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$verify" || fail "没有 v3 签名"
# 签名者那几行的写法随 apksigner 版本变（Signer #1、V3.0 Signer: ……），只认「certificate SHA-256 digest」
signers=$(sed -n 's/.* certificate SHA-256 digest: //p' <<<"$verify" | sort -u)
[ "$signers" = "$expected" ] || fail "签名者不是正式版密钥：$signers"
[ "$(grep -c '^Signer #[0-9]* in lineage certificate SHA-256 digest: ' <<<"$history")" -eq 2 ] \
  || fail "轮换证明不是两把"
grep -qx "Signer #1 in lineage certificate SHA-256 digest: $test_key" <<<"$history" \
  || fail "轮换证明的第一把不是仓库里的测试密钥"
grep -qx "Signer #2 in lineage certificate SHA-256 digest: $expected" <<<"$history" \
  || fail "轮换证明的最后一把不是正式版密钥"
previous=$(awk '/^Signer #1 in lineage/ { s = 1 } /^Signer #2 in lineage/ { s = 0 } s' <<<"$history")
grep -Eq '^Has installed data capability *: true$' <<<"$previous" || fail "旧密钥没有「已装数据」权限"
grep -Eq '^Has rollback capability *: false$' <<<"$previous" || fail "旧密钥带着「回滚」权限"
