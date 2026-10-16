import { create } from "zustand";
import { call } from "electron-sidecar-client";

export const previewFiles = ["index.html", "main.js"] as const;
export type PreviewFile = (typeof previewFiles)[number];
export const previewUrl = "/api/preview/files/";

interface PreviewState {
  files: Record<PreviewFile, string> | null;
  file: PreviewFile;
  /** Counts saves; keys the iframe, so a save reloads it. */
  saves: number;
  load: () => Promise<void>;
  open: (file: PreviewFile) => void;
  edit: (text: string) => void;
  save: () => Promise<void>;
}

// Load once, though React runs effects twice in development.
let loading: Promise<void> | null = null;

export const usePreview = create<PreviewState>()((set, get) => ({
  files: null,
  file: "index.html",
  saves: 0,
  load: () => {
    loading ??= (async () => {
      const texts = await Promise.all(previewFiles.map((file) => call(`preview/read/${file}`)));
      set({ files: Object.fromEntries(previewFiles.map((file, i) => [file, texts[i]])) as Record<PreviewFile, string> });
    })().catch((e: unknown) => {
      loading = null;
      throw e;
    });
    return loading;
  },
  open: (file) => set({ file }),
  edit: (text) => set((s) => (s.files ? { files: { ...s.files, [s.file]: text } } : {})),
  save: async () => {
    const { files } = get();
    if (!files) return;
    await Promise.all(previewFiles.map((file) => call(`preview/save/${file}`, files[file])));
    set((s) => ({ saves: s.saves + 1 }));
  },
}));
