import type { ReactNode } from "react";
import { Link, Route, Router, Switch, useLocation } from "wouter";
import { EditorPage } from "./pages/EditorPage";
import { HomePage } from "./pages/HomePage";
import { PreviewPage } from "./pages/PreviewPage";

/** The app, under /page/ where the sidecar serves it. State lives in the stores in state/. */
export function App() {
  return (
    <Router base="/page">
      <Layout>
        <Switch>
          <Route path="/" component={HomePage} />
          <Route path="/editor" component={EditorPage} />
          <Route path="/preview" component={PreviewPage} />
          <Route>Not found</Route>
        </Switch>
      </Layout>
    </Router>
  );
}

function Layout({ children }: { children: ReactNode }) {
  return (
    <div className="app">
      <nav className="nav">
        <strong>Electron Sidecar Sample</strong>
        <NavLink href="/">Home</NavLink>
        <NavLink href="/editor">Editor</NavLink>
        <NavLink href="/preview">Preview</NavLink>
      </nav>
      <main className="page">{children}</main>
    </div>
  );
}

/** A link marked while its page is shown. */
function NavLink({ href, children }: { href: string; children: ReactNode }) {
  const [location] = useLocation();
  return (
    <Link href={href} aria-current={location === href ? "page" : undefined}>
      {children}
    </Link>
  );
}
