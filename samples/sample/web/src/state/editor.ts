import type { editor } from "monaco-editor/editor";
import { create } from "zustand";

/**
 * The editor's text and, per file path, Monaco's view state (cursor, selections, scroll, folding), so both survive
 * navigating away from the editor and back. Undo history survives too, in Monaco's model, which CodeEditor keeps
 * alive per path. All of it lasts while the app runs.
 */
interface EditorState {
  code: string;
  setCode: (code: string) => void;
  viewStates: Record<string, editor.ICodeEditorViewState>;
  saveViewState: (path: string, state: editor.ICodeEditorViewState) => void;
}

export const useEditor = create<EditorState>()((set) => ({
  code: 'fun main() {\n    println("Hello from Monaco")\n}\n',
  setCode: (code) => set({ code }),
  viewStates: {},
  saveViewState: (path, state) => set((s) => ({ viewStates: { ...s.viewStates, [path]: state } })),
}));
