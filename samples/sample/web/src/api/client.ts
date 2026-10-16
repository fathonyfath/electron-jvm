// Talks to the sidecar that serves this page. The shell adds the token to the window's requests, so these are plain
// fetch and EventSource calls.

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** POSTs [body] as text to `/api/<method>` and returns the answer's text; a failure throws an [ApiError]. */
export async function call(method: string, body = ""): Promise<string> {
  const response = await fetch(`/api/${method}`, { method: "POST", body });
  const text = await response.text();
  if (!response.ok) throw new ApiError(response.status, text);
  return text;
}

/** Listens to the server-sent [event] on `/api/<stream>`; returns the function that stops listening. */
export function listen(stream: string, event: string, onData: (data: string) => void): () => void {
  const source = new EventSource(`/api/${stream}`);
  source.addEventListener(event, (e: MessageEvent<string>) => onData(e.data));
  return () => source.close();
}
