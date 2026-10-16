import { create } from "zustand";
import { listen } from "electron-sidecar-client";

/** Lines for the log: calls the page made and events the sidecar pushed. */
interface EventLog {
  lines: string[];
  add: (line: string) => void;
}

export const useEventLog = create<EventLog>()((set) => ({
  lines: [],
  add: (line) => set((log) => ({ lines: [...log.lines.slice(-199), line] })),
}));

// One stream for the whole page, so ticks keep arriving whichever route is shown.
listen("events", "tick", (n) => useEventLog.getState().add(`<= tick ${n}`));
