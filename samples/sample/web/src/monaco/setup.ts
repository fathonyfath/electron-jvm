// Monaco from the npm package, not the default CDN, which the page's policy refuses and which needs a network. The
// slim entry with only the languages the sample highlights: the full one adds about 9 MB of language workers.
import { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor";
import EditorWorker from "monaco-editor/editor/editor.worker.js?worker";
import "monaco-editor/features/register.all";
import "monaco-editor/languages/definitions/html/register";
import "monaco-editor/languages/definitions/javascript/register";
import "monaco-editor/languages/definitions/kotlin/register";

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

loader.config({ monaco });
