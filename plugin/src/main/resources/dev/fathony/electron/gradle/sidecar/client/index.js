// Calls to the page's own sidecar; the shell adds the token.

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

export async function call(method, body = "") {
  const response = await fetch(`/api/${method}`, { method: "POST", body });
  const text = await response.text();
  if (!response.ok) throw new ApiError(response.status, text);
  return text;
}

export function listen(stream, event, onData) {
  const source = new EventSource(`/api/${stream}`);
  source.addEventListener(event, (e) => onData(e.data));
  return () => source.close();
}
