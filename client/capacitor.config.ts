import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.fluxframe.app',
  appName: '内网图片管理',
  webDir: 'dist',
  backgroundColor: '#0b1020',
  android: {
    allowMixedContent: true,
  },
  // 主界面由原生启动页探测到内网服务地址后动态加载，无需 server.url
}

export default config
