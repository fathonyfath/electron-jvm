import { useEffect, useRef } from "react";
import { useEventLog } from "../state/events";

/** The event log, scrolled to its latest line. */
export function Log() {
  const lines = useEventLog((log) => log.lines);
  const end = useRef<HTMLDivElement>(null);
  // Braces: an effect's return value is its cleanup, and scrollIntoView returns a Promise in current Chromium.
  useEffect(() => {
    end.current?.scrollIntoView({ block: "end" });
  }, [lines]);
  return (
    <pre className="log">
      {lines.join("\n")}
      <div ref={end} />
    </pre>
  );
}
