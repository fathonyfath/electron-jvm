import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { ApiError, call } from "../api/client";
import { Log } from "../components/Log";
import { useEventLog } from "../state/events";

export const Route = createFileRoute("/")({ component: Home });

const wasmGc = (() => {
  try {
    // The smallest module that uses a GC type; it validates only in engines with WasmGC.
    return WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 127, 0]));
  } catch {
    return false;
  }
})();

function Home() {
  const [text, setText] = useState("hello from the page");
  const add = useEventLog((log) => log.add);

  async function run(method: string, body?: string) {
    try {
      add(`-> ${method}(${body ?? ""})\n<- ${await call(method, body)}`);
    } catch (e) {
      add(`-> ${method}(${body ?? ""})\n<- error ${e instanceof ApiError ? e.status : ""}: ${(e as Error).message}`);
    }
  }

  return (
    <>
      <h1>Electron with JVM sidecars</h1>
      <p>
        The page and its API come from a JVM sidecar over HTTP; Electron only draws the window.{" "}
        <a href="https://www.electronjs.org/">Links</a> open in the system browser.
      </p>
      <pre className="info">
        {`user agent: ${navigator.userAgent}\nwasmGC: ${wasmGc}`}
      </pre>
      <form
        className="ask"
        onSubmit={(e) => {
          e.preventDefault();
          void run("reverse", text);
        }}
      >
        <input value={text} onChange={(e) => setText(e.target.value)} aria-label="text" />
        <button title="Sends the text to the JVM, which answers with it reversed">Reverse on the JVM</button>
        <button type="button" title="A handler that suspends for two seconds" onClick={() => void run("wait", "2000")}>
          Wait 2 s
        </button>
        <button type="button" title="A handler that throws" onClick={() => void run("fail")}>
          Fail
        </button>
        <button type="button" title="This sidecar asks the worker sidecar to shout the text" onClick={() => void run("shout", text)}>
          Shout via the worker
        </button>
      </form>
      <p>Ticks arrive once a second over server-sent events, on every page; a wait does not hold them up.</p>
      <Log />
    </>
  );
}
