-- 数据同步到 Linux/Docker 部署后的「容器内路径修正」
-- 本地库里的 storageDir / aiModel 是 Windows 绝对路径，直接生效会让服务写到容器里的假目录
-- 用法：docker exec -i fluxframe-postgres psql -U postgres -d image_manager -v ON_ERROR_STOP=1 < fix-settings.sql
\set ON_ERROR_STOP on

UPDATE "SystemSetting" SET value = to_jsonb('/data/storage'::text), "updatedAt" = now() WHERE key = 'storageDir';
UPDATE "SystemSetting" SET value = to_jsonb(4311), "updatedAt" = now() WHERE key = 'port';
UPDATE "SystemSetting" SET value = to_jsonb('qwen2.5-vl-7b-instruct'::text), "updatedAt" = now() WHERE key = 'aiModel';
UPDATE "SystemSetting" SET value = to_jsonb(false), "updatedAt" = now() WHERE key = 'aiEnabled';
DELETE FROM "SystemSetting" WHERE key = 'ffmpegPath' AND value #>> '{}' LIKE '%:\\%';

SELECT key, left(value::text, 90) AS value FROM "SystemSetting" ORDER BY key;
