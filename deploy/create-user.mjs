// 在容器内创建/重置管理员账户（密码用与 Fastify 服务相同的 argon2 哈希）
//
// 用法（在宿主机）：
//   docker cp create-user.mjs fluxframe-app:/app/server/create-user.mjs
//   docker exec -e ADMIN_USERNAME=<用户名> -e ADMIN_PASSWORD=<密码> \
//     -w /app/server fluxframe-app node create-user.mjs
//   docker exec fluxframe-app rm -f /app/server/create-user.mjs
//
// 账号密码只从环境变量读，**不要写回本文件**——它是要进版本库的。
import argon2 from 'argon2'
import { PrismaClient } from '@prisma/client'

const USERNAME = process.env.ADMIN_USERNAME
const PASSWORD = process.env.ADMIN_PASSWORD
const ROLE = process.env.ADMIN_ROLE || 'ADMIN'

if (!USERNAME || !PASSWORD) {
  console.error('缺少 ADMIN_USERNAME / ADMIN_PASSWORD 环境变量。示例：')
  console.error('  docker exec -e ADMIN_USERNAME=admin -e ADMIN_PASSWORD=xxxxxx \\')
  console.error('    -w /app/server fluxframe-app node create-user.mjs')
  process.exit(1)
}
// 与服务端 /api/auth/register 的校验保持一致，避免建出登不进去或过弱的账号
if (USERNAME.length < 3) {
  console.error('用户名至少 3 个字符')
  process.exit(1)
}
if (PASSWORD.length < 8) {
  console.error('密码至少 8 个字符')
  process.exit(1)
}

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
