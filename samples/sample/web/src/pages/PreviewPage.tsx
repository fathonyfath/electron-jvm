import { useEffect } from "react";
import { ApiError } from "electron-sidecar-client";
import { CodeEditor } from "../components/CodeEditor";
import { useEventLog } from "../state/events";
import { previewFiles, previewUrl, usePreview } from "../state/preview";

const languages = { "index.html": "html", "main.js": "javascript" } as const;

export function PreviewPage() {
  const { files, file, saves, load, open, edit, save } = usePreview();
  const add = useEventLog((log) => log.add);

  useEffect(() => {
    load().catch((e: unknown) => report("loading the preview", e));
  }, [load]);

  function report(what: string, e: unknown) {
    add(`<- ${what} failed: ${e instanceof ApiError ? `error ${e.status}: ` : ""}${(e as Error).message}`);
  }

  async function saveAndReload() {
    try {
      await save();
      add("<- the preview's files, saved in the sidecar's data folder");
    } catch (e) {
      report("saving the preview", e);
    }
  }

  if (!files) return <p>Loading the preview…</p>;

  return (
    <div className="editor-page">
      <div className="toolbar">
        <div className="tabs">
          {previewFiles.map((name) => (
            <button key={name} aria-pressed={name === file} onClick={() => open(name)}>
              {name}
            </button>
          ))}
        </div>
        <span>Saved in the sidecar's data folder, so they survive relaunching the app.</span>
        <button onClick={() => void saveAndReload()}>Save and reload</button>
      </div>
      <div className="split">
        <div className="editor">
          <CodeEditor path={`preview/${file}`} value={files[file]} onChange={edit} language={languages[file]} />
        </div>
        {/* Framing it needs the preview's contentSecurityPolicy(frames = true). */}
        <iframe key={saves} className="preview" src={previewUrl} title="Preview" />
      </div>
    </div>
  );
}
