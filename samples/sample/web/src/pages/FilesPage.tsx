import { type DragEvent, useState } from "react";
import { ApiError, call, electronShell } from "electron-sidecar-client";

const shell = electronShell();

/** A file the page has: dropped on it, or written by the sidecar, and the sidecar's copy, which drags out. */
interface Kept {
  name: string;
  size: number | null;
  /** Where the dropped file is on disk, from the shell; null for one that is not, or outside the app. */
  from: string | null;
  /** The sidecar's copy in its data folder. */
  copy: string;
}

/**
 * Files in and out of the window. A drop gives the page each file's contents, which go to the sidecar, and through the
 * shell its path on disk; the sidecar keeps a copy, and the copy drags out of the window to the desktop or another app.
 */
export function FilesPage() {
  const [kept, setKept] = useState<Kept[]>([]);
  const [over, setOver] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const keep = async (file: File) => {
    const response = await fetch(`/api/files/keep?name=${encodeURIComponent(file.name)}`, { method: "POST", body: file });
    if (!response.ok) throw new ApiError(response.status, await response.text());
    return response.text();
  };

  const onDrop = async (event: DragEvent) => {
    event.preventDefault();
    setOver(false);
    setError(null);
    // Items are readable only during the drop, so everything is taken from them before the first await.
    const dropped = Array.from(event.dataTransfer.items)
      .filter((item) => item.kind === "file")
      .map((item) => ({ file: item.getAsFile()!, folder: item.webkitGetAsEntry()?.isDirectory ?? false }));
    for (const { file, folder } of dropped) {
      const from = shell?.pathForFile(file) ?? null;
      if (folder) {
        setError(`${file.name} is a folder${from ? `, at ${from}` : ""}: this page keeps files only.`);
        continue;
      }
      try {
        const copy = await keep(file);
        setKept((list) => [{ name: file.name, size: file.size, from, copy }, ...list]);
      } catch (e) {
        setError(`${file.name}: ${(e as Error).message}`);
      }
    }
  };

  const writeNote = async () => {
    try {
      const copy = await call("files/note");
      setKept((list) => [{ name: copy.slice(copy.search(/[^\\/]+$/)), size: null, from: null, copy }, ...list]);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      <h1>Files in and out</h1>
      <p>
        Drop files here: the page sends their contents to the sidecar, which keeps a copy in its data folder, and the
        shell says where each one is on disk. Drag a copy out of the window to the desktop or another app.
      </p>
      {!shell && <p className="note">Outside the app's window there are no paths, and nothing drags out.</p>}
      <div
        className={`drop-zone${over ? " over" : ""}`}
        onDragOver={(event) => {
          event.preventDefault();
          event.dataTransfer.dropEffect = "copy";
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={(event) => void onDrop(event)}
      >
        Drop files here
      </div>
      <p>
        <button type="button" onClick={() => void writeNote()}>
          Write a note
        </button>{" "}
        has the sidecar write a file, to drag out.
      </p>
      {error && <p className="error">{error}</p>}
      <ul className="files">
        {kept.map((file) => (
          <li
            key={file.copy}
            draggable={shell !== null}
            title={shell ? "Drag it out of the window" : undefined}
            onDragStart={(event) => {
              // The browser's own drag would carry only the row's text; the shell drags the file itself.
              event.preventDefault();
              shell?.startDrag(file.copy);
            }}
          >
            <strong>{file.name}</strong>
            {file.size !== null && <span> · {file.size.toLocaleString()} bytes</span>}
            <div className="path">dropped from: {file.from ?? (file.size === null ? "written by the sidecar" : "not on disk")}</div>
            <div className="path">copy: {file.copy}</div>
          </li>
        ))}
      </ul>
    </>
  );
}
