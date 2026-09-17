#!/usr/bin/env bash
# 列出 llama.cpp 最近发布的 Linux 安装包命名与 tag 命名（只读）
echo "--- recent tags ---"
curl -s -m 30 'https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=8' \
  | grep -o '"tag_name": "[^"]*"' | head -10

TAG=$(curl -s -m 30 'https://api.github.com/repos/ggml-org/llama.cpp/releases/latest' \
  | grep -o '"tag_name": "[^"]*"' | head -1 | cut -d'"' -f4)
echo "latest release tag: $TAG"
echo "--- assets of latest ---"
curl -s -m 30 "https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/$TAG" \
  | grep -o '"name": "[^"]*"' | cut -d'"' -f4 | head -30

NIGHTLY=$(curl -s -m 30 'https://api.github.com/repos/ggml-org/llama.cpp/releases/latest' \
  | grep -o '"body": "[^"]*b[0-9]*"' | head -1)
echo "--- assets of nightly b10964 (ubuntu only) ---"
curl -s -m 30 'https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/b10964' \
  | grep -o '"name": "[^"]*"' | cut -d'"' -f4 | grep -Ei 'ubuntu|linux' | head -20
echo "--- HEAD check of a guessed ubuntu asset ---"
curl -s -o /dev/null -w 'ubuntu-x64:%{http_code}\n' -L -r 0-0 \
  'https://github.com/ggml-org/llama.cpp/releases/download/b10964/llama-b10964-bin-ubuntu-x64.zip'
