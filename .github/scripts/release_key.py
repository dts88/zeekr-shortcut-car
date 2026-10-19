#!/usr/bin/env python3
"""
正式版的签名密钥。

正式版 APK 由 CI 在打 v* 标签时重新签名（sign_release.sh）：从仓库里公开的测试密钥 keystore/release.jks
轮换到这把私有密钥（APK Signature Scheme v3）。私钥只在两处：项目所有者手上，和 GitHub 上只许 v* 标签用的
release 环境里。

  python release_key.py new <输出文件.p12>
      项目所有者在自己电脑上跑一次。密码在本机输入（不显示），生成 RSA 4096 密钥和 30 年的自签证书，
      存成 PKCS#12，打印证书的 SHA-256 指纹（公开信息）。最后问要不要上传到 GitHub：建 release 环境
      （只许 v* 标签用），存入密钥和密码，记下指纹。上传用本机已登录的 gh。

  python release_key.py upload <密钥文件.p12>
      只上传（上次没传、传到一半失败，或者 GitHub 上的要重放）：输入密码，确认打得开，再照上面上传。

  python release_key.py self-test <输出文件.p12>
      签名自检用的一次性密钥：密码取环境变量 RELEASE_KEYSTORE_PASSWORD，只打印指纹。

需要 Python 3.8+ 和 cryptography。
"""

import base64
import datetime
import getpass
import hashlib
import json
import os
import subprocess
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

REPO = "dts88/zeekr-shortcut-car"
ENVIRONMENT = "release"
# sign_release.sh 按这个别名取密钥
ALIAS = b"zeekr-release"
VALID_YEARS = 30


def generate(path, password):
    """生成密钥写进 path（已有的文件不覆盖），返回 (文件内容, 证书 SHA-256)。"""
    key = rsa.generate_private_key(public_exponent=65537, key_size=4096)
    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "Zeekr Shortcut"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "dts88"),
    ])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=365 * VALID_YEARS))
            .sign(key, hashes.SHA256()))
    # apksigner 用 Java 17 的 KeyStore 读它：认 PBES2 / AES-256 加密和 HMAC-SHA256 校验
    encryption = (serialization.PrivateFormat.PKCS12.encryption_builder()
                  .kdf_rounds(600000)
                  .key_cert_algorithm(pkcs12.PBES.PBESv2SHA256AndAES256CBC)
                  .hmac_hash(hashes.SHA256())
                  .build(password.encode("ascii")))
    data = pkcs12.serialize_key_and_certificates(ALIAS, key, cert, None, encryption)
    # 'x'：已有的文件不覆盖 —— 盖掉一把在用的密钥就再也找不回来
    with open(path, "xb") as f:
        f.write(data)
    return data, hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()


def gh(*args, stdin=None):
    subprocess.run(["gh", *args], input=stdin, check=True)


def upload(data, password, fingerprint):
    """建 release 环境（只许 v* 标签用），存入密钥、密码和指纹。值都从标准输入交给 gh，不出现在命令行上。"""
    environment = f"repos/{REPO}/environments/{ENVIRONMENT}"
    policy = {"deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True}}
    gh("api", "-X", "PUT", environment, "--input", "-", stdin=json.dumps(policy).encode())
    existing = subprocess.run(
        ["gh", "api", f"{environment}/deployment-branch-policies",
         "--jq", '.branch_policies[] | .type + " " + .name'],
        capture_output=True, text=True, check=True).stdout.splitlines()
    if "tag v*" not in existing:
        gh("api", "-X", "POST", f"{environment}/deployment-branch-policies", "-f", "name=v*", "-f", "type=tag")
    gh("secret", "set", "RELEASE_KEYSTORE_B64", "--env", ENVIRONMENT, "--repo", REPO,
       stdin=base64.b64encode(data))
    gh("secret", "set", "RELEASE_KEYSTORE_PASSWORD", "--env", ENVIRONMENT, "--repo", REPO,
       stdin=password.encode("ascii"))
    gh("variable", "set", "RELEASE_CERT_SHA256", "--env", ENVIRONMENT, "--repo", REPO, "--body", fingerprint)


def confirm(question):
    return input(question + " (y/N) ").strip().lower() == "y"


def main():
    # Windows 上输出被转接时（Git Bash 等）默认按本地代码页写，中文会乱
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    if len(sys.argv) != 3 or sys.argv[1] not in ("new", "upload", "self-test"):
        sys.exit(__doc__)
    mode, path = sys.argv[1], sys.argv[2]

    if mode == "upload":
        with open(path, "rb") as f:
            data = f.read()
        password = getpass.getpass("密钥密码：")
        try:
            bundle = pkcs12.load_pkcs12(data, password.encode("ascii"))
        except (ValueError, UnicodeEncodeError):
            sys.exit("密码不对，或者不是这个脚本生成的密钥文件。")
        fingerprint = hashlib.sha256(bundle.cert.certificate.public_bytes(serialization.Encoding.DER)).hexdigest()
        print("证书 SHA-256 指纹：", fingerprint)
        upload(data, password, fingerprint)
        print("已上传：release 环境只许 v* 标签用，存入了密钥和密码，指纹记为 RELEASE_CERT_SHA256。")
        return

    if os.path.exists(path):
        sys.exit(f"{path} 已存在，不覆盖。")

    if mode == "self-test":
        password = os.environ.get("RELEASE_KEYSTORE_PASSWORD", "")
        if not password:
            sys.exit("需要环境变量 RELEASE_KEYSTORE_PASSWORD")
        print(generate(path, password)[1])
        return

    print("密钥文件将保存到：", os.path.abspath(path))
    print("请放在只有你掌握的地方（建议 U 盘），另外再备份一份；不要放进任何仓库、网络盘或公司的 OneDrive。")
    print("密钥或密码丢了，以后的版本就只能先卸载再安装。")
    if not confirm("继续？"):
        sys.exit("已取消。")
    password = getpass.getpass("设置密码（至少 12 位，只用英文字母、数字和符号）：")
    if len(password) < 12 or not password.isascii() or not password.isprintable():
        sys.exit("密码太短或含其他字符，没有生成。")
    if password != getpass.getpass("再输一遍："):
        sys.exit("两次不一致，没有生成。")

    data, fingerprint = generate(path, password)
    print("已生成：", os.path.abspath(path))
    print("证书 SHA-256 指纹：", fingerprint)
    if confirm("现在上传到 GitHub 的 release 环境（只许 v* 标签用）？"):
        upload(data, password, fingerprint)
        print("已上传：release 环境只许 v* 标签用，存入了密钥和密码，指纹记为 RELEASE_CERT_SHA256。")
    else:
        print("没有上传。")


if __name__ == "__main__":
    main()
