#!/usr/bin/env python3
"""
CMY-Pro jar 版本注入 + 重新签名
用法: python3 inject_version.py <jar> <private_key.der> <version> [<commit_sha>]
功能:
  1. 向 jar 写入 assets/hmcl.properties（hmcl.version=版本号），使启动器不再判定为开发版
  2. 按 HMCL IntegrityChecker 算法重新生成 META-INF/hmcl_signature
     （对按字典序排列的全部条目，SHA-512(条目名) || SHA-512(条目内容) 拼接后 RSA-SHA512 签名）
输出: 覆盖原 jar
"""
import hashlib
import subprocess
import sys
import zipfile


def main():
    jar = sys.argv[1]
    key = sys.argv[2]
    version = sys.argv[3]
    commit = sys.argv[4] if len(sys.argv) > 4 else ""

    # 1. 读取全部条目
    entries = {}
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            entries[info.filename] = z.read(info.filename)

    # 2. 合并注入 assets/hmcl.properties（保留原有键，只更新版本相关）
    props = {}
    if "assets/hmcl.properties" in entries:
        for line in entries["assets/hmcl.properties"].decode("utf-8", "replace").splitlines():
            line = line.strip()
            if line and "=" in line:
                k, v = line.split("=", 1)
                props[k] = v
    props["hmcl.version"] = version
    props["hmcl.version.type"] = "stable"
    if commit:
        props["hmcl.version.hash"] = commit
    entries["assets/hmcl.properties"] = ("\n".join(f"{k}={v}" for k, v in props.items()) + "\n").encode("utf-8")

    # 3. 计算 HMCL 自签名
    payload = b""
    for name in sorted(entries):
        if name == "META-INF/hmcl_signature":
            continue
        payload += hashlib.sha512(name.encode("utf-8")).digest()
        payload += hashlib.sha512(entries[name]).digest()

    sig = subprocess.run(
        ["openssl", "dgst", "-sha512", "-sign", key],
        input=payload, capture_output=True, check=True,
    ).stdout

    # 4. 重写 jar：全部条目 + 新签名
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(entries):
            if name == "META-INF/hmcl_signature":
                continue
            z.writestr(name, entries[name])
        z.writestr("META-INF/hmcl_signature", sig)

    print(f"[inject] version={version} -> {jar} ({len(entries)} entries, signature re-signed)")


if __name__ == "__main__":
    main()
