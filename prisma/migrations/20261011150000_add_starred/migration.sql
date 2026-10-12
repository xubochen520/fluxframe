-- 收藏（星标）：图片库可按收藏筛选，排序时置顶
ALTER TABLE "Image" ADD COLUMN "starred" BOOLEAN NOT NULL DEFAULT false;

-- 收藏筛选索引（deletedAt 已有联合索引，这里补一条 starred 的过滤路径）
CREATE INDEX "Image_starred_idx" ON "Image"("starred") WHERE "deletedAt" IS NULL;
