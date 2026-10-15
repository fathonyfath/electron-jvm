import Editor, { type OnMount } from "@monaco-editor/react";
import { useEditor } from "../state/editor";

interface Props {
  /** The file this editor shows; Monaco keeps one model, with its undo history, per path. */
  path: string;
  value: string;
  onChange: (value: string) => void;
  language?: string;
}

/**
 * Monaco, filling its container; the text is owned by the caller. The model stays alive when the editor unmounts
 * (`keepCurrentModel`), and the view state is saved to the editor store whenever the cursor moves, the view scrolls
 * or the editor loses focus, then restored on the next mount.
 */
export function CodeEditor({ path, value, onChange, language = "kotlin" }: Props) {
  const onMount: OnMount = (editor) => {
    const saved = useEditor.getState().viewStates[path];
    if (saved) editor.restoreViewState(saved);
    editor.focus();

    const save = () => {
      const state = editor.saveViewState();
      if (state) useEditor.getState().saveViewState(path, state);
    };
    editor.onDidChangeCursorSelection(save);
    editor.onDidScrollChange(save);
    editor.onDidBlurEditorWidget(save);
  };

  return (
    <Editor
      path={path}
      keepCurrentModel
      value={value}
      language={language}
      onChange={(text) => onChange(text ?? "")}
      onMount={onMount}
      options={{ minimap: { enabled: false }, fontSize: 14, automaticLayout: true, scrollBeyondLastLine: false }}
    />
  );
}
