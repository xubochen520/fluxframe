-- DeepSeek 记账：本地 API KEY（余额/账户）+ 每日用量（余额差值记账 & 平台真实用量）

-- CreateTable
CREATE TABLE "DeepseekKey" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "apiKey" TEXT NOT NULL,
    "accountName" TEXT NOT NULL DEFAULT '',
    "platformKeyId" TEXT NOT NULL DEFAULT '',
    "enabled" BOOLEAN NOT NULL DEFAULT true,
    "currency" TEXT,
    "balance" DOUBLE PRECISION,
    "grantedBalance" DOUBLE PRECISION,
    "toppedUpBalance" DOUBLE PRECISION,
    "isAvailable" BOOLEAN,
    "lastObservedAt" TIMESTAMP(3),
    "lastError" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DeepseekKey_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DeepseekUsageDaily" (
    "id" TEXT NOT NULL,
    "day" TEXT NOT NULL,
    "keyId" TEXT NOT NULL DEFAULT '',
    "platformKeyId" TEXT NOT NULL DEFAULT '',
    "platformKeyName" TEXT NOT NULL DEFAULT '',
    "currency" TEXT NOT NULL DEFAULT 'CNY',
    "amount" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "refill" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "tokensHit" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "tokensMiss" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "tokensOut" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "requests" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "models" JSONB,
    "source" TEXT NOT NULL DEFAULT 'ledger',
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DeepseekUsageDaily_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "DeepseekKey_accountName_idx" ON "DeepseekKey"("accountName");

-- CreateIndex
CREATE UNIQUE INDEX "DeepseekUsageDaily_day_keyId_platformKeyId_key" ON "DeepseekUsageDaily"("day", "keyId", "platformKeyId");

-- CreateIndex
CREATE INDEX "DeepseekUsageDaily_day_idx" ON "DeepseekUsageDaily"("day");

-- CreateIndex
CREATE INDEX "DeepseekUsageDaily_keyId_idx" ON "DeepseekUsageDaily"("keyId");

-- CreateIndex
CREATE INDEX "DeepseekUsageDaily_platformKeyId_idx" ON "DeepseekUsageDaily"("platformKeyId");
