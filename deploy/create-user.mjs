// 在容器内创建/重置管理员账户（密码用与 Fastify 服务相同的 argon2 哈希）
// 用法（在宿主机）：
//   docker cp create-user.mjs fluxframe-app:/app/server/create-user.mjs
//   docker exec -w /app/server fluxframe-app node create-user.mjs
//   docker exec fluxframe-app rm -f /app/server/create-user.mjs
import argon2 from 'argon2'
import { PrismaClient } from '@prisma/client'

const USERNAME = 'admin'
const PASSWORD = 'REDACTED-PASSWORD'
const ROLE = 'ADMIN'

const prisma = new PrismaClient()
try {
  const before = await prisma.user.findUnique({ where: { username: USERNAME } })
  const passwordHash = await argon2.hash(PASSWORD)
  const user = await prisma.user.upsert({
    where: { username: USERNAME },
    update: { passwordHash, role: ROLE },
    create: { username: USERNAME, passwordHash, role: ROLE },
  })
  // 改密码后清掉该账户的旧会话，避免沿用同步过来的旧登录态
  const cleared = await prisma.session.deleteMany({ where: { userId: user.id } })
  console.log(JSON.stringify({
    ok: true,
    username: user.username,
    role: user.role,
    action: before ? 'updated' : 'created',
    sessionsCleared: cleared.count,
  }))
} finally {
  await prisma.$disconnect()
}
