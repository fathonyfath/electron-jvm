/** An error answer: its status and message. */
export declare class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string);
}

/** POSTs [body] to `/api/<method>` and returns the answer's text; throws [ApiError] on failure. */
export declare function call(method: string, body?: string): Promise<string>;

/** Listens to [event] on the event stream `/api/<stream>`; returns a function that stops. */
export declare function listen(stream: string, event: string, onData: (data: string) => void): () => void;
