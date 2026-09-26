#!/usr/bin/env python3
"""生成/补全 Release 说明并上传构建产物（供 GitHub Actions 调用）。

用法: python3 .github/scripts/release_notes.py <tag>

行为:
  - 取「上一 tag..当前 tag」之间的提交记录（标题 + 正文；合并提交会带来 PR 描述）作为更新内容
  - Release 不存在则创建；已存在则“追加/替换”自动生成段落（标记 <!-- AUTO-CHANGELOG --> 之后的内容）
  - 手写的说明保留在标记之前，不会被覆盖
  - 把 dist/ 下的 APK 全部上传（--clobber 覆盖旧同名文件）
"""
import os
import subprocess
import sys


def sh(*args, check=True):
    return subprocess.run(args, capture_output=True, text=True, check=check)


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1]:
        tag = sys.argv[1]
    else:
        tag = sh("git", "describe", "--tags", "--abbrev=0", check=False).stdout.strip()
    if not tag:
        print("no tag resolved")
        return 1

    prev = sh("git", "describe", "--tags", "--abbrev=0", f"{tag}^", check=False).stdout.strip()
    rng = f"{prev}..{tag}" if prev else tag
    log = sh(
        "git", "log", "--no-merges",
        "--pretty=format:%s%n%n%b%n---", rng, check=False,
    ).stdout.strip()
    log = log.rstrip("- \n")

    exists = subprocess.run(
        ["gh", "release", "view", tag], capture_output=True, text=True
    ).returncode == 0
    body = ""
    if exists:
        body = sh("gh", "release", "view", tag, "--json", "body", "-q", ".body", check=False).stdout or ""

    marker = "<!-- AUTO-CHANGELOG -->"
    artifacts = sorted(
        os.path.basename(p) for p in (os.path.join("dist", f) for f in os.listdir("dist"))
    ) if os.path.isdir("dist") else []
    artifact_lines = "\n".join(f"- 附件：`{name}`" for name in artifacts)

    auto = (
        f"{marker}\n\n"
        f"## 🧾 更新内容（自动生成 · {tag}）\n\n"
        f"{log or '- *暂无提交记录*'}\n\n"
        f"### 📦 构建产物\n{artifact_lines or '- 构建产物见附件'}\n\n"
        f"*本篇由 GitHub Actions 依据提交记录自动编译生成*"
    )

    if marker in body:
        head = body.split(marker)[0].rstrip()
        final = f"{head}\n\n{auto}" if head else auto
    else:
        final = f"{body.rstrip()}\n\n{auto}" if body.strip() else auto

    if exists:
        sh("gh", "release", "edit", tag, "--notes", final)
    else:
        sh("gh", "release", "create", tag, "--title", tag, "--notes", final, "--verify-tag")

    if os.path.isdir("dist") and artifacts:
        sh("gh", "release", "upload", tag, *[f"dist/{f}" for f in artifacts], "--clobber")

    print(f"release updated: {tag} ({len(artifacts)} artifacts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
