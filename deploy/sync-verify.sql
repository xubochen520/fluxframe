-- 同步后核对：容器内数据库行数（应与本机一致）
-- 用法：docker exec -i fluxframe-postgres psql -U postgres -d image_manager -f - < sync-verify.sql
\pset pager off
SELECT
  (SELECT count(*) FROM "User") AS users,
  (SELECT count(*) FROM "Image") AS images,
  (SELECT count(*) FROM "Image" WHERE "mimeType" LIKE 'video/%') AS videos,
  (SELECT count(*) FROM "Tag") AS tags,
  (SELECT count(*) FROM "ImageTag") AS image_tags,
  (SELECT count(*) FROM "ImageVariant") AS variants,
  (SELECT count(*) FROM "AuditLog") AS audit,
  (SELECT count(*) FROM "DeepseekKey") AS ds_keys;
SELECT username, role FROM "User" ORDER BY "createdAt";
SELECT pg_size_pretty(sum(size)) AS image_bytes, count(*) AS measured FROM "Image";
SELECT key, left(value::text, 60) AS value FROM "SystemSetting" WHERE key IN ('storageDir', 'port', 'aiModel', 'aiEnabled') ORDER BY key;
