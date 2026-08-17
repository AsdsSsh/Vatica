import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { ThemeProvider } from "./theme";
import { BackendStatusProvider } from "./backendStatus";
import { AuthProvider } from "./auth";

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <ThemeProvider>
      <BackendStatusProvider>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BackendStatusProvider>
    </ThemeProvider>
  </React.StrictMode>,
);
