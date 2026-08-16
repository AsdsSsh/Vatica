import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// @ts-expect-error process is a nodejs global
const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig(async () => ({
  plugins: [react()],

  build: {
    rollupOptions: {
      output: {
        // 迭代 12 I12-10：拆包降低主 chunk 体积，antd/React/Markdown 独立缓存
        manualChunks(id: string) {
          if (!id.includes("node_modules")) return undefined;
          if (id.includes("@uiw/react-markdown-preview")) return "markdown";
          if (
            id.includes("antd") ||
            id.includes("@ant-design/icons") ||
            id.includes("@rc-component") ||
            id.includes("rc-")
          ) {
            return "antd";
          }
          if (id.includes("react")) return "react";
          return undefined;
        },
      },
    },
  },

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      // 3. tell Vite to ignore watching `src-tauri`
      ignored: ["**/src-tauri/**"],
    },
  },
}));
