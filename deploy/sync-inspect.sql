\pset pager off
SELECT key, left(value::text, 160) AS value FROM "SystemSetting" ORDER BY key;
SELECT
  (SELECT count(*) FROM "User") AS users,
  (SELECT count(*) FROM "Image") AS images,
  (SELECT count(*) FROM "Image" WHERE "deletedAt" IS NULL) AS alive,
  (SELECT count(*) FROM "Image" WHERE "mimeType" LIKE 'video/%') AS videos,
  (SELECT count(*) FROM "Tag") AS tags,
  (SELECT count(*) FROM "ImageTag") AS image_tags,
  (SELECT count(*) FROM "ImageVariant") AS variants,
  (SELECT count(*) FROM "AuditLog") AS audit,
  (SELECT count(*) FROM "DeepseekKey") AS ds_keys,
  (SELECT count(*) FROM "DeepseekUsageDaily") AS ds_days;
SELECT username, role, "createdAt" FROM "User" ORDER BY "createdAt";
SELECT pg_size_pretty(sum(size)) AS image_bytes, sum(size) AS bytes FROM "Image";
SELECT count(*) AS uploaded_jobs FROM "UploadJob";
