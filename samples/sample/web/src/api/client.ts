// Talks to the sidecar that serves this page. The shell puts the per-launch token and every sidecar's port in the
// URL fragment, which never reaches a server; it is read once, kept in memory only, and cleared from the address bar.
const fragment = new URLSearchParams(location.hash.slice(1));
const token = fragment.get("token") ?? "";

/** Every sidecar the shell started, name to port. */
export const sidecars: Record<string, number> = JSON.parse(fragment.get("sidecars") || "{}");

history.replaceState(history.state, "", location.pathname + location.search);

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
  const response = await fetch(`/api/${method}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body,
  });
  const text = await response.text();
  if (!response.ok) throw new ApiError(response.status, text);
  return text;
}

/** Listens to the server-sent [event] on `/api/<stream>`; returns the function that stops listening. */
export function listen(stream: string, event: string, onData: (data: string) => void): () => void {
  // EventSource cannot send headers, so the token goes in the query.
  const source = new EventSource(`/api/${stream}?token=${encodeURIComponent(token)}`);
  const handler = (e: MessageEvent<string>) => onData(e.data);
  source.addEventListener(event, handler);
  return () => source.close();
}
