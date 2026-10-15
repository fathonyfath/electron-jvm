// Monaco from the npm package rather than @monaco-editor/react's default CDN, which the page's Content-Security-Policy
// refuses and which would not work offline. The slim entry with every editor feature and only the languages the
// sample edits: the full entry also bundles TypeScript, CSS, HTML and JSON language services, about 9 MB of workers.
import { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor";
import EditorWorker from "monaco-editor/editor/editor.worker.js?worker";
import "monaco-editor/features/register.all";
import "monaco-editor/languages/definitions/kotlin/register";

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

loader.config({ monaco });
