import { ApiError, call } from "electron-sidecar-client";
import { CodeEditor } from "../components/CodeEditor";
import { useEditor } from "../state/editor";
import { useEventLog } from "../state/events";

export function EditorPage() {
  const code = useEditor((editor) => editor.code);
  const setCode = useEditor((editor) => editor.setCode);
  const add = useEventLog((log) => log.add);

  async function reverse() {
    try {
      setCode(await call("reverse", code));
      add("<- the editor's text, reversed on the JVM");
    } catch (e) {
      add(`<- error ${e instanceof ApiError ? e.status : ""}: ${(e as Error).message}`);
    }
  }

  return (
    <div className="editor-page">
      <div className="toolbar">
        <span>Text, cursor, scroll, folding and undo history survive switching pages.</span>
        <button onClick={() => void reverse()}>Reverse on the JVM</button>
      </div>
      <div className="editor">
        <CodeEditor path="main.kt" value={code} onChange={setCode} />
      </div>
    </div>
  );
}
