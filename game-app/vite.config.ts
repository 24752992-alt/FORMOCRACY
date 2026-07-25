import { createLogger, defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { blockRemoteImportsPlugin } from './plugins/vite-plugin-block-remote'
import { blockAudioApisPlugin } from './plugins/vite-plugin-block-audio-apis'
import { blockDeviceEventsPlugin } from './plugins/vite-plugin-block-device-events'
import { blockScancodeApisPlugin } from './plugins/vite-plugin-block-scancode-apis'
import { blockGoogleFontImportPlugin } from './plugins/vite-plugin-block-google-font-import'
import { collectLingguangUsagePlugin } from './plugins/vite-plugin-collect-lingguang-usage'
import { blockFileInputPlugin } from './plugins/vite-plugin-block-file-input'
import { blockFetchPlugin } from './plugins/vite-plugin-block-fetch'
import { blockParentWindowAccessPlugin } from './plugins/vite-plugin-block-parent-window-access'
import { blockFormSubmitPlugin } from './plugins/vite-plugin-block-form-submit'
import { blockReactRouterDomRoutersPlugin } from './plugins/vite-plugin-block-react-router-dom-routers'
import { rewriteSafeAreaEnvPlugin } from './plugins/vite-plugin-rewrite-safe-area-env'
import { asapBuildContractPlugin } from './plugins/vite-plugin-asap-build-contract'
import { injectManifestPlugin } from './plugins/vite-plugin-inject-manifest'
import { validateManifestPlugin } from './plugins/vite-plugin-validate-manifest'
import { instrumentCatchPlugin } from './plugins/vite-plugin-instrument-catch'

const viteLogger = createLogger()
const originalWarn = viteLogger.warn.bind(viteLogger)

viteLogger.warn = (msg, options) => {
  if (msg.includes(`can't be bundled without type="module" attribute`)) {
    return
  }
  originalWarn(msg, options)
}

// https://vite.dev/config/
export default defineConfig({
  customLogger: viteLogger,
  plugins: [
    react(),
    blockRemoteImportsPlugin(),
    blockGoogleFontImportPlugin(),
    blockAudioApisPlugin(),
    blockDeviceEventsPlugin(),
    blockScancodeApisPlugin(),
    blockFileInputPlugin(),
    blockFetchPlugin(),
    blockParentWindowAccessPlugin(),
    blockFormSubmitPlugin(),
    blockReactRouterDomRoutersPlugin(),
    rewriteSafeAreaEnvPlugin(),
    instrumentCatchPlugin(),
    validateManifestPlugin(),
    injectManifestPlugin(),
    collectLingguangUsagePlugin(),
    asapBuildContractPlugin(),
  ],
  build: {
    sourcemap: 'hidden', // 生成 .map 文件
  },
  base: './',
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
