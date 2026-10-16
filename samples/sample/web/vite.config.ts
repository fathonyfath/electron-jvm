import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  // The sidecar serves the page at /page/.
  base: "/page/",
  build: {
    // Gradle's buildPage passes its output directory; a plain `npm run build` writes dist/.
    outDir: process.env.PAGE_OUT ?? "dist",
    emptyOutDir: true,
    // In development builds (Gradle passes PAGE_SOURCE_MAPS), DevTools shows errors at their source file and line; a
    // packaged app carries no page sources.
    sourcemap: process.env.PAGE_SOURCE_MAPS === "true",
  },
});
