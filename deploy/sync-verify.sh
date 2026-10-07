#!/usr/bin/env bash
# 数据同步后核验（在目标服务器执行）
#   校验：账户登录/角色 → 可见条数与库内总数（扣除 r18）一致 → 图片/视频文件可读 → 容器状态
# 用法：bash deploy/sync-verify.sh
set -uo pipefail
cd "${APP_DIR:-$HOME/fluxframe}"
PORT="$(grep -E '^APP_PORT=' deploy/.env | cut -d= -f2 | tr -d '"')"; PORT="${PORT:-4311}"
BASE="http://127.0.0.1:${PORT}"
JAR="$(mktemp)"
PSQL=(docker exec fluxframe-postgres psql -U postgres -d image_manager -t -A)
q() { "${PSQL[@]}" -c "$1" 2>/dev/null | tr -d '\r' | head -1; }
# 管理员账号只从环境变量或 deploy/.env 读，不写进脚本——本文件会进版本库。
ADMIN_USER="${ADMIN_USER:-$(grep -E '^ADMIN_USERNAME=' deploy/.env 2>/dev/null | cut -d= -f2 | tr -d '"')}"
ADMIN_PASS="${ADMIN_PASS:-$(grep -E '^ADMIN_PASSWORD=' deploy/.env 2>/dev/null | cut -d= -f2 | tr -d '"')}"
[ -n "${ADMIN_USER}" ] && [ -n "${ADMIN_PASS}" ] || { echo "缺少 ADMIN_USERNAME / ADMIN_PASSWORD：请写进 deploy/.env 或设为环境变量" >&2; exit 1; }
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "  PASS  $1（$3）"; pass=$((pass+1)); else echo "  FAIL  $1（期望 $2，实际 $3）"; fail=$((fail+1)); fi; }

echo "=== 账户 ==="
check "$ADMIN_USER 登录" "200" "$(curl -s -c "$JAR" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "$BASE/api/auth/login")"
ME="$(curl -s -b "$JAR" "$BASE/api/me")"
echo "  /api/me → $ME"
check "角色为 ADMIN" "yes" "$(echo "$ME" | grep -q '"role":"ADMIN"' && echo yes || echo no)"

echo
echo "=== 数据一致性（库内总数 vs 接口可见数） ==="
IMG_TOTAL="$(q 'SELECT count(*) FROM "Image" WHERE "deletedAt" IS NULL')"
IMG_R18="$(q 'SELECT count(DISTINCT i.id) FROM "Image" i JOIN "ImageTag" it ON it."imageId"=i.id JOIN "Tag" t ON t.id=it."tagId" WHERE t.r18')"
TAG_TOTAL="$(q 'SELECT count(*) FROM "Tag"')"
TAG_R18="$(q 'SELECT count(*) FROM "Tag" WHERE r18')"
VID_TOTAL="$(q 'SELECT count(*) FROM "Image" WHERE "mimeType" LIKE '"'"'video/%'"'"' AND "deletedAt" IS NULL')"
echo "  库内：图片 ${IMG_TOTAL}（其中带 r18 标签 ${IMG_R18}）、标签 ${TAG_TOTAL}（r18 ${TAG_R18}）、视频 ${VID_TOTAL}"
IMGS="$(curl -s -b "$JAR" "$BASE/api/images")"
check "接口可见图片数 = 总数 - r18" "$((IMG_TOTAL - IMG_R18))" "$(echo "$IMGS" | grep -o '"id":"' | wc -l)"
check "接口可见标签数 = 总数 - r18" "$((TAG_TOTAL - TAG_R18))" "$(curl -s -b "$JAR" "$BASE/api/tags" | grep -o '"id"' | wc -l)"
check "接口可见视频数" "$VID_TOTAL" "$(echo "$IMGS" | grep -o '"mimeType":"video/' | wc -l)"

echo
echo "=== 文件可读（storageDir 生效） ==="
IMG_ID="$(q 'SELECT id FROM "Image" WHERE "mimeType" LIKE '"'"'image/%'"'"' AND "deletedAt" IS NULL ORDER BY "uploadedAt" DESC LIMIT 1')"
VID_ID="$(q 'SELECT id FROM "Image" WHERE "mimeType" LIKE '"'"'video/%'"'"' LIMIT 1')"
for u in variant/320 variant/768 file download; do
  check "图片 /${u}" "200" "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' -L "$BASE/api/images/${IMG_ID}/${u}")"
done
check "视频 /file" "200" "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/images/${VID_ID}/file")"
echo "  （视频没有缩略图变体，/variant 返回 404 属正常设计）"

echo
echo "=== 磁盘文件清点 ==="
echo "  originals : $(find deploy/data/storage/originals -type f | wc -l) 个 / 库内 ${IMG_TOTAL} 条记录（含已删）"
echo "  thumbnails: $(find deploy/data/storage/thumbnails -type f | wc -l) 个 / 库内 $(q 'SELECT count(*) FROM "ImageVariant"') 个变体"

echo
echo "=== 容器状态 ==="
docker compose -f deploy/docker-compose.yml ps --format '  {{.Name}}  {{.Status}}'
rm -f "$JAR"
echo
echo "==================== 通过 $pass 项，失败 $fail 项 ===================="
[ "$fail" -eq 0 ]
