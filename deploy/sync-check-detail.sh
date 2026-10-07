#!/usr/bin/env bash
# 解释 API 计数差异并复核文件可读性（在目标服务器执行）
# 用法：bash deploy/sync-check-detail.sh
set -uo pipefail
cd "${APP_DIR:-$HOME/fluxframe}"
PORT="$(grep -E '^APP_PORT=' deploy/.env | cut -d= -f2 | tr -d '"')"; PORT="${PORT:-4311}"
BASE="http://127.0.0.1:${PORT}"
PSQL=(docker exec fluxframe-postgres psql -U postgres -d image_manager -t -A)

echo "=== r18 与可见性（解释 API 计数少于库内总数） ==="
"${PSQL[@]}" -c "SELECT 'tags_total='||count(*)||' r18_tags='||count(*) FILTER (WHERE r18)||' person_tags='||count(*) FILTER (WHERE person) FROM \"Tag\""
"${PSQL[@]}" -c "SELECT 'images_total='||count(*)||' images_with_r18_tag='||count(DISTINCT i.id) FILTER (WHERE t.r18) FROM \"Image\" i LEFT JOIN \"ImageTag\" it ON it.\"imageId\"=i.id LEFT JOIN \"Tag\" t ON t.id=it.\"tagId\""
"${PSQL[@]}" -c "SELECT 'trashed='||count(*) FROM \"Image\" WHERE \"deletedAt\" IS NOT NULL"
echo "可见 = 总数 - 带 r18 标签（前端默认不显示 r18 内容）"

echo
echo "=== 文件清点 ==="
echo "originals : $(find deploy/data/storage/originals -type f | wc -l) 个"
echo "thumbnails: $(find deploy/data/storage/thumbnails -type f | wc -l) 个"
"${PSQL[@]}" -c "SELECT 'db_variants='||count(*) FROM \"ImageVariant\""
"${PSQL[@]}" -c "SELECT 'db_images='||count(*) FROM \"Image\""

echo
echo "=== 用真实图片（非视频）复核文件接口 ==="
# 管理员账号只从环境变量或 deploy/.env 读，不写进脚本——本文件会进版本库。
ADMIN_USER="${ADMIN_USER:-$(grep -E '^ADMIN_USERNAME=' deploy/.env 2>/dev/null | cut -d= -f2 | tr -d '"')}"
ADMIN_PASS="${ADMIN_PASS:-$(grep -E '^ADMIN_PASSWORD=' deploy/.env 2>/dev/null | cut -d= -f2 | tr -d '"')}"
[ -n "${ADMIN_USER}" ] && [ -n "${ADMIN_PASS}" ] || { echo "缺少 ADMIN_USERNAME / ADMIN_PASSWORD：请写进 deploy/.env 或设为环境变量" >&2; exit 1; }
JAR="$(mktemp)"
curl -s -c "$JAR" -o /dev/null -H 'Content-Type: application/json' -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "$BASE/api/auth/login"
IMG_ID="$("${PSQL[@]}" -c "SELECT id FROM \"Image\" WHERE \"mimeType\" LIKE 'image/%' AND \"deletedAt\" IS NULL ORDER BY \"uploadedAt\" DESC LIMIT 1" | tr -d '\r')"
echo "  抽样图片：$IMG_ID"
for u in "variant/320" "variant/768" "file" "download"; do
  printf '  %-12s http=%s\n' "$u" "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' -L "$BASE/api/images/${IMG_ID}/${u}")"
done
VID_ID="$("${PSQL[@]}" -c "SELECT id FROM \"Image\" WHERE \"mimeType\" LIKE 'video/%' LIMIT 1" | tr -d '\r')"
echo "  抽样视频：$VID_ID（视频无缩略图变体，variant 404 属正常）"
printf '  video file   http=%s\n' "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/images/${VID_ID}/file")"
rm -f "$JAR"
