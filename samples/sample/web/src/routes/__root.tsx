import { Link, Outlet, createRootRoute } from "@tanstack/react-router";

export const Route = createRootRoute({ component: Layout });

function Layout() {
  return (
    <div className="app">
      <nav className="nav">
        <strong>Electron Sidecar Sample</strong>
        <Link to="/" activeOptions={{ exact: true }}>
          Home
        </Link>
        <Link to="/editor">Editor</Link>
      </nav>
      <main className="page">
        <Outlet />
      </main>
    </div>
  );
}
