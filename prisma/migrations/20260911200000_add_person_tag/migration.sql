-- 人物组标签：把标签标记为「人名」，前端用绿色圆点区分并归入人物组
ALTER TABLE "Tag" ADD COLUMN "person" BOOLEAN NOT NULL DEFAULT false;
