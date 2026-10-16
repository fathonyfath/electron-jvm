// The page's entry: Monaco's setup and the styles, then the router.
import "./monaco/setup";
import "./styles/app.css";
import { RouterProvider, createRouter } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { routeTree } from "./routeTree.gen";

// The sidecar serves the page at /page/.
const router = createRouter({ routeTree, basepath: "/page" });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
