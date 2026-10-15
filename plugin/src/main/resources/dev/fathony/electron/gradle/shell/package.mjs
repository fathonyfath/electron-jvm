// Packages the app for this platform with the desktop folder (runtime and sidecars) as Resources/desktop, into
// PACKAGE_OUT/<name>-<platform>-<arch>.
import { packager } from "@electron/packager";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const paths = await packager({
  dir: here,
  out: process.env.PACKAGE_OUT,
  name: process.env.APP_NAME,
  overwrite: true,
  extraResource: [process.env.DESKTOP_DIR],
  ignore: [/^\/package\.mjs$/],
});
console.log(`packaged ${paths.join(", ")}`);
